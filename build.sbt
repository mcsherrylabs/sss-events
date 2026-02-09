import sbt.Keys.resolvers

// Publishing metadata (required by sbt-ci-release)
ThisBuild / organization := "com.mcsherrylabs"
ThisBuild / homepage := Some(url("https://github.com/mcsherrylabs/sss-events"))
ThisBuild / licenses := List("GPL-3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.en.html"))
ThisBuild / developers := List(
  Developer(
    id = "mcsherrylabs",
    name = "Alan McSherry",
    email = "",
    url = url("http://mcsherrylabs.com")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/mcsherrylabs/sss-events"),
    "scm:git@github.com:mcsherrylabs/sss-events.git"
  )
)

// Core library project (implicit root)
lazy val core = (project in file("."))
  .settings(
    name := "sss-events",
    version := "0.0.11",
    organization := "com.mcsherrylabs",
    scalaVersion := "3.6.4",

    Compile / compile / javacOptions ++= Seq("--release", "17"),

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-core" % "1.5.12" % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.12" % Test,
      "com.typesafe" % "config" % "1.4.3",
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.6"
    ),

    resolvers ++= Seq(
      "stepsoft" at "https://nexus.mcsherrylabs.com/repository/releases",
      "stepsoft-snapshots" at "https://nexus.mcsherrylabs.com/repository/snapshots"
    ),

    // Test coverage settings
    coverageMinimumStmtTotal := 60,
    coverageMinimumBranchTotal := 50,
    coverageFailOnMinimum := false,
    coverageHighlighting := true,

    // Scaladoc settings
    Compile / doc / scalacOptions ++= Seq(
      "-doc-title", "sss-events API Documentation",
      "-doc-version", version.value,
      "-doc-root-content", baseDirectory.value + "/scaladoc-root.md"
    ),

    // Include scaladoc in published artifacts
    Compile / packageDoc / publishArtifact := true
  )

// Benchmarks project with JMH
lazy val benchmarks = (project in file("benchmarks"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "sss-events-benchmarks",
    organization := "com.mcsherrylabs",
    scalaVersion := "3.6.4",

    Compile / compile / javacOptions ++= Seq("--release", "17"),

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "ch.qos.logback" % "logback-core" % "1.5.12",
      "ch.qos.logback" % "logback-classic" % "1.5.12"
    ),

    resolvers ++= Seq(
      "stepsoft" at "https://nexus.mcsherrylabs.com/repository/releases",
      "stepsoft-snapshots" at "https://nexus.mcsherrylabs.com/repository/snapshots"
    ),

    // Don't publish benchmarks
    publish / skip := true,
    publishLocal / skip := true,

    // JMH settings
    Jmh / version := "1.37"
  )
