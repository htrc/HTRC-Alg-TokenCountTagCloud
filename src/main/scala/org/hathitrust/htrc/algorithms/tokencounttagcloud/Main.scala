package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.io.{File, FileOutputStream, OutputStreamWriter, PrintWriter}
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
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
import org.hathitrust.htrc.data.TextOptions.{DehyphenateAtEol, RemoveEmptyLines, TrimLines}
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
    val conf = new Conf(args)
    val numPartitions = conf.numPartitions.toOption
    val numCores = conf.numCores.map(_.toString).getOrElse("*")
    val dataApiUrl = conf.dataApiUrl()
    val pairtreeRootPath = conf.pairtreeRootPath.toOption.map(_.toString)
    val outputPath = conf.outputPath()
    val language = conf.language()
    val correctionsUrl = conf.correctionsUrl.toOption
    val stopWordsUrl = conf.stopWordsUrl.toOption
    val maxTokensToDisplay = conf.maxDisplay()
    val lowercaseBeforeCounting = conf.lowercaseBeforeCounting()
    val tagCloudTokenRegex = conf.tagCloudTokenFilter.toOption.map(_.r)
    val htids = conf.htids.toOption match {
      case Some(file) => Source.fromFile(file).getLines().toSeq
      case None => Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toSeq
    }

    val sparkConf = new SparkConf()
    sparkConf.setAppName(appName)
    sparkConf.setIfMissing("spark.master", s"local[$numCores]")

    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()

    val sc = spark.sparkContext

    try {
      logger.info("Starting...")
      logger.debug(s"Using $numCores cores")

      val t0 = Timer.nanoClock()
      outputPath.mkdirs()

      type Token = String
      type Replacement = String
      type Correction = (Token, Replacement)

      val stopwordsBcast = {
        val stopwords = stopWordsUrl match {
          case Some(url) =>
            logger.info("Loading stop words from {}", url)
            Source.fromURL(url)(Codec.UTF8).getLines().toSet

          case None => Set.empty[Token]
        }

        sc.broadcast(stopwords)
      }

      val correctionsBcast = {
        val corrections = correctionsUrl match {
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

      val idsRDD = numPartitions match {
        case Some(n) => sc.parallelize(htids, n) // split input into n partitions
        case None => sc.parallelize(htids) // use default number of partitions
      }

      val volumeErrAcc = new ErrorAccumulator[String, String](identity)(sc)

      val volumesRDD = pairtreeRootPath match {
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
          val dataApiToken = Option(System.getenv("DATAAPI_TOKEN")) match {
            case Some(token) => token
            case None => throw new RuntimeException("DATAAPI_TOKEN environment variable is missing")
          }

          logger.info("Processing volumes from {}", dataApiUrl)

          idsRDD.mapPartitions { ids =>
            val dataApi = DataApiClient.Builder()
              .setApiUrl(dataApiUrl.toString)
              .setAuthToken(dataApiToken)
              .setUseTempStorage(failOnError = false)
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
          }
      }

      val tokenCounts =
        volumesRDD
          .flatMap(_.structuredPages.map(_.body(TrimLines, RemoveEmptyLines, DehyphenateAtEol)))
          .flatMap { pageText =>
            var text = pageText
            val nlp = NLPInstances.forLanguage(language).getOrElse {
              // replace zero-width spaces with actual spaces
              text = text.replaceAll("""\p{Cf}""", " ")
              NLPInstances.whitespaceTokenizer
            }

            val locale = Locale.forLanguageTag(language)

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
                      case Some(correction) if lowercaseBeforeCounting => correction
                      case Some(correction) =>
                        checkTokenFormat(token) match {
                          case UpperCase => correction.toUpperCase(locale)
                          case LowerCase => correction
                          case SentenceCase if correction.length >= 2 => correction.head.toUpper + correction.tail.toLowerCase(locale)
                          case _ => correction
                        }
                      case None if lowercaseBeforeCounting => tokenLowerCase
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
        volumeErrAcc.saveErrors(new Path(outputPath.toString, "volume_errors.txt"), _.toString)
      }

      val tokenCountsCsvFile = new File(outputPath, "token_counts.csv")
      val tagCloudHtmlFile = new File(outputPath, "tag_cloud.html")

      logger.info("Saving token counts...")
      val writer = new OutputStreamWriter(new FileOutputStream(tokenCountsCsvFile), Codec.UTF8.charSet)
      val csvConfig = rfc.withHeader("token", "count")
      using(writer.asCsvWriter[(Token, Int)](csvConfig)) { out =>
        out.write(tokenCounts)
      }

      logger.info("Saving token counts tag cloud...")
      using(new PrintWriter(tagCloudHtmlFile, Codec.UTF8.name)) { out =>
        val it = tokenCounts.iterator
        val filteredTokenCounts = tagCloudTokenRegex match {
          case Some(regex) => it.filter { case (token, _) => regex.findFirstMatchIn(token).isDefined }
          case None => it
        }
        val tokens = mutable.MutableList.empty[Token]
        val counts = mutable.MutableList.empty[Int]
        for ((token, count) <- filteredTokenCounts.take(maxTokensToDisplay)) {
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
