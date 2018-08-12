package org.hathitrust.htrc.algorithms.tokencounttagcloud

import java.net.URL

object Defaults {
  val DATAAPI_URL: URL = new URL("https://dataapi-algo.htrc.indiana.edu")
  val LOWERCASE: Boolean = false
  val MAXDISPLAY: Int = 200
  val NUMCORES: String = "*"
}
