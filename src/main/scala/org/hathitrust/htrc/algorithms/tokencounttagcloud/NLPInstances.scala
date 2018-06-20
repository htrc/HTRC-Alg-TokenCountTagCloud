package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.util.{Locale, Properties}

import edu.stanford.nlp.pipeline.StanfordCoreNLP
import Helper.{loadPropertiesFromClasspath, logger}

import scala.util.{Failure, Success}

object NLPInstances {
  private val instances: Map[String, StanfordCoreNLP] = Map(createInstances(): _*)

  val whitespaceTokenizer: StanfordCoreNLP = {
    val props = new Properties()
    props.put("annotators", "tokenize")
    props.put("tokenize.language", "Whitespace")
    new StanfordCoreNLP(props)
  }

  private def createInstances(): List[(String, StanfordCoreNLP)] = {
    for (lang <- Main.supportedLanguages.toList) yield {
      val langProps = s"/nlp/config/$lang.properties"
      logger.debug(s"Loading $lang settings from $langProps...")
      val props = loadPropertiesFromClasspath(langProps) match {
        case Success(p) => p
        case Failure(e) =>
          logger.error(s"Unable to load $lang settings", e)
          throw e
      }

      lang -> new StanfordCoreNLP(props)
    }
  }

  def forLocale(locale: Locale): Option[StanfordCoreNLP] = instances.get(locale.getLanguage)

  def forLanguage(lang: String): Option[StanfordCoreNLP] = instances.get(lang)
}
