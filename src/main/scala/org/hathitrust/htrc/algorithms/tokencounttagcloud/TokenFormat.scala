package org.hathitrust.htrc.algorithms.tokencounttagcloud

object TokenFormat extends Enumeration {
  type TokenFormat = Value
  val UpperCase, LowerCase, SentenceCase, MixedCase, NoCase = Value
}
