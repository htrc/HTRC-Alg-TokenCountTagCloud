package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.io._
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
import com.typesafe.config.ConfigFactory
import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.pipeline.Annotation
import html.TagCloud
import kantan.csv._
import kantan.csv.ops._
import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession
import org.hathitrust.htrc.algorithms.tokencounttagcloud.Helper._
import org.hathitrust.htrc.algorithms.tokencounttagcloud.TokenFormat._
import org.hathitrust.htrc.algorithms.tokencounttagcloud.stanfordnlp.NLPInstances
import org.hathitrust.htrc.data.ops.TextOptions._
import org.hathitrust.htrc.data.{HtrcVolume, HtrcVolumeId}
import org.hathitrust.htrc.tools.dataapi.DataApiClient
import org.hathitrust.htrc.tools.scala.io.IOUtils.using
import org.hathitrust.htrc.tools.spark.errorhandling.ErrorAccumulator
import org.hathitrust.htrc.tools.spark.errorhandling.RddExtensions._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.{Codec, Source, StdIn}

/**
  * Performs token count and generates a tag cloud for a given list of HT volume ids
  *
  * @author Boris Capitanu
  */
object Main {
  val appName: String = "token-count-tag-cloud"
  val supportedLanguages: Set[String] = Set("ar", "zh", "en", "fr", "de", "es")

  def stopSparkAndExit(sc: SparkContext, exitCode: Int = 0): Unit = {
    try {
      sc.stop()
    } finally {
      System.exit(exitCode)
    }
  }

  def main(args: Array[String]): Unit = {
    val (conf, input) =
      if (args.contains("--config")) {
        val configArgs = new ConfigFileArg(args)
        val input = configArgs.htids.toOption
        Conf.fromConfig(ConfigFactory.parseFile(configArgs.configFile()).getConfig(appName)) -> input
      } else {
        val cmdLineArgs = new CmdLineArgs(args)
        val input = cmdLineArgs.htids.toOption
        Conf.fromCmdLine(cmdLineArgs) -> input
      }

    val htids = input match {
      case Some(file) => Source.fromFile(file).getLines().toSeq
      case None => Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toSeq
    }

    val sparkConf = new SparkConf()
    sparkConf.setAppName(appName)
    sparkConf.setIfMissing("spark.master", s"local[${conf.numCores}]")

    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()

    val sc = spark.sparkContext

    try {
      logger.info("Starting...")
      logger.debug(s"Using ${conf.numCores} cores")

      val t0 = Timer.nanoClock()
      conf.outputPath.mkdirs()

      type Token = String
      type Replacement = String
      type Correction = (Token, Replacement)

      val stopwordsBcast = {
        val stopwords = conf.stopWordsUrl match {
          case Some(url) =>
            logger.info("Loading stop words from {}", url)
            Source.fromURL(url)(Codec.UTF8).getLines().toSet

          case None => Set.empty[Token]
        }

        sc.broadcast(stopwords)
      }

      val correctionsBcast = {
        val corrections = conf.correctionsUrl match {
          case Some(url) =>
            logger.info("Loading correction data from {}", url)
            url.asCsvReader[Correction](rfc.withHeader)
              .foldLeft(Map.empty[Token, Replacement]) { (map, res) =>
                res match {
                  case Right(row) => map + row
                  case Left(error) =>
                    throw new RuntimeException(s"Error parsing correction data", error)
                }
              }

          case None => Map.empty[Token, Replacement]
        }

        sc.broadcast(corrections)
      }

      val idsRDD = conf.numPartitions match {
        case Some(n) => sc.parallelize(htids, n) // split input into n partitions
        case None => sc.parallelize(htids) // use default number of partitions
      }

      val volumeErrAcc = new ErrorAccumulator[String, String](identity)(sc)

      val volumesRDD = conf.pairtreeRootPath match {
        case Some(path) =>
          logger.info("Processing volumes from {}", path)
          idsRDD.tryMap { id =>
            val pairtreeVolume =
              HtrcVolumeId
                .parseUnclean(id)
                .map(_.toPairtreeDoc(path))
                .get

            HtrcVolume.from(pairtreeVolume)(Codec.UTF8).get
          }(volumeErrAcc)

        case None =>
          val dataApiUrl = conf.dataApiUrl.get
          val dataApiToken = conf.dataApiToken.get
          val keyStoreFile = conf.keyStoreFile.get
          val keyStorePwd = conf.keyStorePwd.get

          logger.info("Processing volumes from {}", dataApiUrl)

          idsRDD.mapPartitions {
            case ids if ids.nonEmpty =>
              val keyStore = KeyStore.getInstance("PKCS12")
              using(new FileInputStream(keyStoreFile)) { ksf =>
                keyStore.load(ksf, keyStorePwd.toCharArray)
              }

              val dataApi = DataApiClient.Builder()
                .setApiUrl(dataApiUrl.toString)
                .setAuthToken(dataApiToken)
                .setUseTempStorage(failOnError = false)
                .useClientCertKeyStore(keyStore, keyStorePwd)
                .build()

              val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
              val volumeIterator = Await.result(dataApi.retrieveVolumes(ids)(Codec.UTF8, ec), Duration.Inf)
              val volumes = using(volumeIterator)(_
                .withFilter {
                  case Left(_) => true
                  case Right(error) =>
                    logger.error("DataAPI: {}", error.message)
                    false
                }
                .collect { case Left(volume) => volume }
                .toList
              )
              volumes.iterator

            case _ => Iterator.empty
          }
      }

      val tokenCounts =
        volumesRDD
          .flatMap(_.structuredPages.map(_.body(TrimLines, RemoveEmptyLines, DehyphenateAtEol)))
          .flatMap { pageText =>
            var text = pageText
            val nlp = NLPInstances.forLanguage(conf.language).getOrElse {
              // replace zero-width spaces with actual spaces
              text = text.replaceAll("""\p{Cf}""", " ")
              NLPInstances.whitespaceTokenizer
            }

            val locale = Locale.forLanguageTag(conf.language)

            val annotatedText: Annotation = {
              val annotation = new Annotation(text)
              nlp.annotate(annotation)
              annotation
            }

            val stopwords = stopwordsBcast.value
            val corrections = correctionsBcast.value

            val pageTokens =
              annotatedText
                .get(classOf[CoreAnnotations.TokensAnnotation])
                .iterator()
                .asScala
                .map { label =>
                  val token = label.originalText()
                  (token, token.toLowerCase(locale))
                }
                .collect {
                  case (token, tokenLowerCase) if !stopwords.contains(tokenLowerCase) =>
                    corrections.get(tokenLowerCase) match {
                      case Some(correction) if conf.lowercaseBeforeCounting => correction
                      case Some(correction) =>
                        checkTokenFormat(token) match {
                          case UpperCase => correction.toUpperCase(locale)
                          case LowerCase => correction
                          case SentenceCase if correction.length >= 2 => correction.head.toUpper + correction.tail.toLowerCase(locale)
                          case _ => correction
                        }
                      case None if conf.lowercaseBeforeCounting => tokenLowerCase
                      case None => token
                    }
                }

            val pageTokenCounts = pageTokens.foldLeft(Map.empty[Token, Int].withDefaultValue(0))(
              (counts, token) => counts.updated(token, counts(token) + 1))

            pageTokenCounts
          }
          .reduceByKey(_ + _)
          .sortBy(_._2, ascending = false)
          .collect()

      if (volumeErrAcc.nonEmpty) {
        logger.info("Writing error report...")
        volumeErrAcc.saveErrors(new Path(conf.outputPath.toString, "volume_errors.txt"), _.toString)
      }

      val tokenCountsCsvFile = new File(conf.outputPath, "token_counts.csv")
      val tagCloudHtmlFile = new File(conf.outputPath, "tag_cloud.html")

      logger.info("Saving token counts...")
      val writer = new OutputStreamWriter(new FileOutputStream(tokenCountsCsvFile), Codec.UTF8.charSet)
      val csvConfig = rfc.withHeader("token", "count")
      using(writer.asCsvWriter[(Token, Int)](csvConfig)) { out =>
        out.write(tokenCounts)
      }

      logger.info("Saving token counts tag cloud...")
      using(new PrintWriter(tagCloudHtmlFile, Codec.UTF8.name)) { out =>
        val it = tokenCounts.iterator
        val filteredTokenCounts = conf.tagCloudTokenRegex match {
          case Some(regex) => it.filter { case (token, _) => regex.findFirstMatchIn(token).isDefined }
          case None => it
        }
        val tokens = mutable.MutableList.empty[Token]
        val counts = mutable.MutableList.empty[Int]
        for ((token, count) <- filteredTokenCounts.take(conf.maxTokensToDisplay)) {
          tokens += token
          counts += count
        }
        out.write(TagCloud(tokens, counts)(TagCloudConfig(title = "Tag Cloud")).toString)
      }

      val t1 = Timer.nanoClock()
      val elapsed = t1 - t0

      logger.info(f"All done in ${Timer.pretty(elapsed)}")
    }
    catch {
      case e: Throwable =>
        logger.error(s"Uncaught exception", e)
        stopSparkAndExit(sc, exitCode = 500)
    }

    stopSparkAndExit(sc)
  }
}
