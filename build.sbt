import Dependencies._

showCurrentGitBranch

git.useGitDescribe := true

lazy val commonSettings = Seq(
  organization := "org.hathitrust.htrc",
  organizationName := "HathiTrust Research Center",
  organizationHomepage := Some(url("https://www.hathitrust.org/htrc")),
  scalaVersion := "2.12.8",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:postfixOps",
    "-language:implicitConversions"
  ),
  externalResolvers := Seq(
    Resolver.defaultLocal,
    Resolver.mavenLocal,
    "HTRC Nexus Repository" at "http://nexus.htrc.illinois.edu/content/groups/public",
  ),
  packageOptions in (Compile, packageBin) += Package.ManifestAttributes(
    ("Git-Sha", git.gitHeadCommit.value.getOrElse("N/A")),
    ("Git-Branch", git.gitCurrentBranch.value),
    ("Git-Version", git.gitDescribedVersion.value.getOrElse("N/A")),
    ("Git-Dirty", git.gitUncommittedChanges.value.toString),
    ("Build-Date", new java.util.Date().toString)
  )
)

lazy val ammoniteSettings = Seq(
  libraryDependencies +=
    {
      val version = scalaBinaryVersion.value match {
        case "2.10" => "1.0.3"
        case _ ⇒ "1.6.7"
      }
      "com.lihaoyi" % "ammonite" % version % "test" cross CrossVersion.full
    },
  sourceGenerators in Test += Def.task {
    val file = (sourceManaged in Test).value / "amm.scala"
    IO.write(file, """object amm extends App { ammonite.Main.main(args) }""")
    Seq(file)
  }.taskValue,
  fork in (Test, run) := false
)

lazy val `token-count-tag-cloud` = (project in file(".")).
  enablePlugins(SbtTwirl, GitVersioning, GitBranchPrompt, JavaAppPackaging).
  settings(commonSettings).
  settings(ammoniteSettings).
  //settings(spark("2.4.3")).
  settings(spark_dev("2.4.3")).
  settings(
    name := "token-count-tag-cloud",
    licenses += "Apache2" -> url("http://www.apache.org/licenses/LICENSE-2.0"),
    libraryDependencies ++= Seq(
      "org.hathitrust.htrc"           %% "data-model"           % "1.3.1",
      "org.hathitrust.htrc"           %% "dataapi-client"       % "0.9",
      "org.hathitrust.htrc"           %% "scala-utils"          % "2.6",
      "org.hathitrust.htrc"           %% "spark-utils"          % "1.2.0",
      "edu.stanford.nlp"              %  "stanford-corenlp"     % "3.9.2",
      "edu.stanford.nlp"              %  "stanford-corenlp"     % "3.9.2"
        classifier "models"
        classifier "models-arabic"
        classifier "models-chinese"
        classifier "models-english"
        classifier "models-french"
        classifier "models-german"
        classifier "models-spanish",
      "com.nrinaudo"                  %% "kantan.csv"           % "0.5.1",
      "com.typesafe.play"             %% "play-json"            % "2.7.4"
        exclude("com.fasterxml.jackson.core", "jackson-databind")
        exclude("ch.qos.logback", "logback-classic"),
      "com.typesafe"                  %  "config"               % "1.3.4",
      "org.rogach"                    %% "scallop"              % "3.3.1",
      "com.gilt"                      %% "gfc-time"             % "0.0.7",
      "ch.qos.logback"                %  "logback-classic"      % "1.2.3",
      "org.codehaus.janino"           %  "janino"               % "3.0.12",
      "org.scalacheck"                %% "scalacheck"           % "1.14.0"      % Test,
      "org.scalatest"                 %% "scalatest"            % "3.0.8"       % Test
    )
  )
