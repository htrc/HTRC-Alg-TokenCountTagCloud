package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.io.File
import java.net.URL

import com.typesafe.config.Config

import scala.util.matching.Regex

case class Conf(numPartitions: Option[Int],
                numCores: String,
                pairtreeRootPath: Option[String],
                dataApiUrl: Option[URL],
                dataApiToken: Option[String],
                keyStoreFile: Option[File],
                keyStorePwd: Option[String],
                outputPath: File,
                language: String,
                correctionsUrl: Option[URL],
                stopWordsUrl: Option[URL],
                maxTokensToDisplay: Int,
                lowercaseBeforeCounting: Boolean,
                tagCloudTokenRegex: Option[Regex])

object Conf {
  def fromConfig(config: Config): Conf = {
    val numPartitions = if (config.hasPath("num-partitions")) Some(config.getInt("num-partitions")) else None
    val numCores = if (config.hasPath("num-cores")) config.getString("num-cores") else Defaults.NUMCORES
    val pairtreeRootPath = if (config.hasPath("pairtree")) Some(config.getString("pairtree")) else None
    val dataApiUrl = if (config.hasPath("dataapi-url")) Some(new URL(config.getString("dataapi-url"))) else Some(Defaults.DATAAPI_URL)
    val dataApiToken = if (config.hasPath("dataapi-token")) Some(config.getString("dataapi-token")) else None
    val keyStoreFile = if (config.hasPath("keystore")) Some(new File(config.getString("keystore"))) else None
    val keyStorePwd = if (config.hasPath("keystore-pwd")) Some(config.getString("keystore-pwd")) else None
    val outputPath = new File(config.getString("output"))
    val language = config.getString("language")
    val correctionsUrl = if (config.hasPath("corrections-url")) Some(new URL(config.getString("corrections-url"))) else None
    val stopWordsUrl = if (config.hasPath("stopwords-url")) Some(new URL(config.getString("stopwords-url"))) else None
    val maxTokensToDisplay = if (config.hasPath("max-display")) config.getInt("max-display") else Defaults.MAXDISPLAY
    val lowercaseBeforeCounting = if (config.hasPath("lowercase")) config.getBoolean("lowercase") else Defaults.LOWERCASE
    val tagCloudTokenRegex = if (config.hasPath("token-filter")) Some(config.getString("token-filter").r) else None

    Conf(
      numPartitions = numPartitions,
      numCores = numCores,
      pairtreeRootPath = pairtreeRootPath,
      dataApiUrl = dataApiUrl,
      dataApiToken = dataApiToken,
      keyStoreFile = keyStoreFile,
      keyStorePwd = keyStorePwd,
      outputPath = outputPath,
      language = language,
      correctionsUrl = correctionsUrl,
      stopWordsUrl = stopWordsUrl,
      maxTokensToDisplay = maxTokensToDisplay,
      lowercaseBeforeCounting = lowercaseBeforeCounting,
      tagCloudTokenRegex = tagCloudTokenRegex
    )
  }

  def fromCmdLine(cmdLineArgs: CmdLineArgs): Conf = {
    val numPartitions = cmdLineArgs.numPartitions.toOption
    val numCores = cmdLineArgs.numCores.map(_.toString).getOrElse(Defaults.NUMCORES)
    val pairtreeRootPath = cmdLineArgs.pairtreeRootPath.toOption.map(_.toString)
    val dataApiUrl = cmdLineArgs.dataApiUrl.toOption
    val dataApiToken = Option(System.getenv("DATAAPI_TOKEN"))
    val keyStoreFile = cmdLineArgs.keyStore.toOption
    val keyStorePwd = cmdLineArgs.keyStorePwd.toOption
    val outputPath = cmdLineArgs.outputPath()
    val language = cmdLineArgs.language()
    val correctionsUrl = cmdLineArgs.correctionsUrl.toOption
    val stopWordsUrl = cmdLineArgs.stopWordsUrl.toOption
    val maxTokensToDisplay = cmdLineArgs.maxDisplay()
    val lowercaseBeforeCounting = cmdLineArgs.lowercaseBeforeCounting()
    val tagCloudTokenRegex = cmdLineArgs.tagCloudTokenFilter.toOption.map(_.r)

    if (pairtreeRootPath.isEmpty && dataApiToken.isEmpty)
      throw new RuntimeException("DATAAPI_TOKEN environment variable is missing")

    Conf(
      numPartitions = numPartitions,
      numCores = numCores,
      pairtreeRootPath = pairtreeRootPath,
      dataApiUrl = dataApiUrl,
      dataApiToken = dataApiToken,
      keyStoreFile = keyStoreFile,
      keyStorePwd = keyStorePwd,
      outputPath = outputPath,
      language = language,
      correctionsUrl = correctionsUrl,
      stopWordsUrl = stopWordsUrl,
      maxTokensToDisplay = maxTokensToDisplay,
      lowercaseBeforeCounting = lowercaseBeforeCounting,
      tagCloudTokenRegex = tagCloudTokenRegex
    )
  }
}