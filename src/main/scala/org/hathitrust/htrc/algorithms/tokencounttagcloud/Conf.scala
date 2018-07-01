package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.io.File
import java.net.URL

import org.rogach.scallop.{Scallop, ScallopConf, ScallopHelpFormatter, ScallopOption, SimpleOption}

import scala.util.Try

/**
  * Command line argument configuration
  *
  * @param arguments The cmd line args
  */
class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  appendDefaultToDescription = true
  helpFormatter = new ScallopHelpFormatter {
    override def getOptionsHelp(s: Scallop): String = {
      super.getOptionsHelp(s.copy(opts = s.opts.map {
        case opt: SimpleOption if !opt.required =>
          opt.copy(descr = "(Optional) " + opt.descr)
        case other => other
      }))
    }
  }

  private val (appTitle, appVersion, appVendor) = {
    val p = getClass.getPackage
    val nameOpt = Option(p).flatMap(p => Option(p.getImplementationTitle))
    val versionOpt = Option(p).flatMap(p => Option(p.getImplementationVersion))
    val vendorOpt = Option(p).flatMap(p => Option(p.getImplementationVendor))
    (nameOpt, versionOpt, vendorOpt)
  }

  version(appTitle.flatMap(
    name => appVersion.flatMap(
      version => appVendor.map(
        vendor => s"$name $version\n$vendor"))).getOrElse(Main.appName))

  val numPartitions: ScallopOption[Int] = opt[Int]("num-partitions",
    descr = "The number of partitions to split the input set of HT IDs into, " +
      "for increased parallelism",
    argName = "N",
    validate = 0 <
  )

  val numCores: ScallopOption[Int] = opt[Int]("num-cores",
    descr = "The number of CPU cores to use (if not specified, uses all available cores)",
    short = 'c',
    argName = "N",
    validate = 0 <
  )

  val dataApiUrl: ScallopOption[URL] = opt[URL]("dataapi-url",
    descr = "The DataAPI endpoint URL (Note: DATAAPI_TOKEN environment variable must be set)",
    default = Some(new URL("https://dataapi-algo.htrc.indiana.edu/data-api")),
    argName = "URL",
    noshort = true
  )

  val pairtreeRootPath: ScallopOption[File] = opt[File]("pairtree",
    descr = "The path to the pairtree root hierarchy to process",
    argName = "DIR"
  )

  val outputPath: ScallopOption[File] = opt[File]("output",
    descr = "The folder where the output will be written to",
    argName = "DIR",
    required = true
  )

  val language: ScallopOption[String] = opt[String]("language",
    descr = "ISO 639-1 language code ( https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes )",
    argName = "LANG",
    required = true
  )

  val correctionsUrl: ScallopOption[URL] = opt[URL]("corrections-url",
    descr = "The URL containing the correction rules to use",
    argName = "URL"
  )

  val stopWordsUrl: ScallopOption[URL] = opt[URL]("stopwords-url",
    descr = "The URL containing the stop words to remove",
    argName = "URL",
    noshort = true
  )

  val lowercaseBeforeCounting: ScallopOption[Boolean] = opt[Boolean]("lowercase",
    descr = "Lowercase all tokens before counting",
    default = Some(false),
    noshort = true
  )

  val tagCloudTokenFilter: ScallopOption[String] = opt[String]("token-filter",
    descr = "Regular expression which determines which tokens will be displayed in the tag cloud",
    noshort = true,
    validate = regexp => Try(regexp.r).isSuccess
  )

  val maxDisplay: ScallopOption[Int] = opt[Int]("max-display",
    descr = "Display only this many of the most highest-occurring tokens",
    argName = "N",
    default = Some(200)
  )

  val htids: ScallopOption[File] = trailArg[File]("htids",
    descr = "The HT ids to process (if not provided, will read from stdin)"
  )

  validateFileExists(pairtreeRootPath)
  validateFileExists(htids)
  verify()
}