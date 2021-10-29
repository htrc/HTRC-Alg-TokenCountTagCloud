import Dependencies._

showCurrentGitBranch

git.useGitDescribe := true

lazy val commonSettings = Seq(
  organization := "org.hathitrust.htrc",
  organizationName := "HathiTrust Research Center",
  organizationHomepage := Some(url("https://www.hathitrust.org/htrc")),
  scalaVersion := "2.13.6",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:postfixOps",
    "-language:implicitConversions"
  ),
  resolvers ++= Seq(
    Resolver.mavenLocal,
    "HTRC Nexus Repository" at "https://nexus.htrc.illinois.edu/repository/maven-public"
  ),
  externalResolvers := Resolver.combineDefaultResolvers(resolvers.value.toVector, mavenCentral = false),
  Compile / packageBin / packageOptions += Package.ManifestAttributes(
    ("Git-Sha", git.gitHeadCommit.value.getOrElse("N/A")),
    ("Git-Branch", git.gitCurrentBranch.value),
    ("Git-Version", git.gitDescribedVersion.value.getOrElse("N/A")),
    ("Git-Dirty", git.gitUncommittedChanges.value.toString),
    ("Build-Date", new java.util.Date().toString)
  )
)

lazy val wartRemoverSettings = Seq(
  Compile / compile / wartremoverWarnings ++= Warts.unsafe.diff(Seq(
    Wart.DefaultArguments,
    Wart.NonUnitStatements,
    Wart.Any,
    Wart.StringPlusAny,
    Wart.OptionPartial
  ))
)

lazy val buildInfoSettings = Seq(
  buildInfoOptions ++= Seq(BuildInfoOption.BuildTime),
  buildInfoPackage := "utils",
  buildInfoKeys ++= Seq[BuildInfoKey](
    "gitSha" -> git.gitHeadCommit.value.getOrElse("N/A"),
    "gitBranch" -> git.gitCurrentBranch.value,
    "gitVersion" -> git.gitDescribedVersion.value.getOrElse("N/A"),
    "gitDirty" -> git.gitUncommittedChanges.value,
    "nameWithVersion" -> s"${name.value} ${version.value}"
  )
)

lazy val ammoniteSettings = Seq(
  libraryDependencies +=
    {
      val version = scalaBinaryVersion.value match {
        case "2.10" => "1.0.3"
        case _ ⇒  "2.4.0-23-76673f7f"
      }
      "com.lihaoyi" % "ammonite" % version % Test cross CrossVersion.full
    },
  Test / sourceGenerators += Def.task {
    val file = (Test / sourceManaged).value / "amm.scala"
    IO.write(file, """object amm extends App { ammonite.Main.main(args) }""")
    Seq(file)
  }.taskValue,
  connectInput := true,
  outputStrategy := Some(StdoutOutput)
)

lazy val `token-count-tag-cloud` = (project in file("."))
  .enablePlugins(BuildInfoPlugin, SbtTwirl, GitVersioning, GitBranchPrompt, JavaAppPackaging)
  .settings(commonSettings)
  .settings(wartRemoverSettings)
  .settings(buildInfoSettings)
  .settings(ammoniteSettings)
  //.settings(spark("3.2.0"))
  .settings(spark_dev("3.2.0"))
  .settings(
    name := "token-count-tag-cloud",
    licenses += "Apache2" -> url("http://www.apache.org/licenses/LICENSE-2.0"),
    libraryDependencies ++= Seq(
      "org.hathitrust.htrc"           %% "data-model"               % "2.13",
      "org.hathitrust.htrc"           %% "dataapi-client"           % "1.0",
      "org.hathitrust.htrc"           %% "scala-utils"              % "2.13",
      "org.hathitrust.htrc"           %% "spark-utils"              % "1.4",
      "edu.stanford.nlp"              %  "stanford-corenlp"         % "4.3.1",
      "edu.stanford.nlp"              %  "stanford-corenlp"         % "4.3.1"
        classifier "models"
        classifier "models-arabic"
        classifier "models-chinese"
        classifier "models-english"
        classifier "models-french"
        classifier "models-german"
        classifier "models-spanish",
      "com.nrinaudo"                  %% "kantan.csv"               % "0.6.2",
      "com.typesafe.play"             %% "play-json"                % "2.9.2"
        exclude("com.fasterxml.jackson.core", "jackson-databind")
        exclude("ch.qos.logback", "logback-classic"),
      "com.typesafe"                  %  "config"                   % "1.4.1",
      "org.rogach"                    %% "scallop"                  % "4.0.3",
      "ch.qos.logback"                %  "logback-classic"          % "1.2.6",
      "org.codehaus.janino"           %  "janino"                   % "3.0.8",  // versions > 3.0.8 are not working
      "org.scalacheck"                %% "scalacheck"               % "1.15.4"  % Test,
      "org.scalatest"                 %% "scalatest"                % "3.2.10"  % Test,
      "org.scalatestplus"             %% "scalacheck-1-15"          % "3.2.9.0" % Test
    ),
    dependencyOverrides ++= Seq(
      "com.google.guava" % "guava" % "15.0"
    ),
    Test / parallelExecution := false,
    Test / fork := true
  )
