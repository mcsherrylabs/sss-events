import sbt.Keys.resolvers

// Publishing settings (shared by core)
lazy val publishingSettings = Seq(
  publishMavenStyle := true,
  updateOptions := updateOptions.value.withGigahorse(false),
  pomIncludeRepository := { _ => false },
  Test / publishArtifact := false,
  usePgpKeyHex("F4ED23D42A612E27F11A6B5AF75482A04B0D9486"),

  // Publish to Sonatype OSS by default, or to private Nexus if PUBLISH_TO_NEXUS env var is set
  publishTo := {
    sys.env.get("PUBLISH_TO_NEXUS") match {
      case Some("true") =>
        val nexus = "https://nexus.mcsherrylabs.com/"
        if (isSnapshot.value)
          Some("snapshots" at nexus + "repository/snapshots")
        else
          Some("releases" at nexus + "repository/releases")
      case _ =>
        val sonaUrl = "https://oss.sonatype.org/"
        if (isSnapshot.value)
          Some("snapshots" at sonaUrl + "content/repositories/snapshots")
        else
          Some("releases" at sonaUrl + "service/local/staging/deploy/maven2")
    }
  },

  // Credentials for both repositories
  credentials ++= Seq(
    // Sonatype OSS credentials
    sys.env.get("SONA_USER").map(userName => Credentials(
      "Sonatype Nexus Repository Manager",
      "oss.sonatype.org",
      userName,
      sys.env.getOrElse("SONA_PASS", ""))
    ).getOrElse(
      Credentials(Path.userHome / ".ivy2" / ".credentials")
    ),
    // Private Nexus credentials (for when PUBLISH_TO_NEXUS=true)
    sys.env.get("NEXUS_USER").map(userName => Credentials(
      "Sonatype Nexus Repository Manager",
      "nexus.mcsherrylabs.com",
      userName,
      sys.env.getOrElse("NEXUS_PASS", ""))
    ).getOrElse(
      Credentials(Path.userHome / ".ivy2" / ".credentials")
    )
  ),

  pomExtra := (
    <url>https://github.com/mcsherrylabs/sss-events</url>
    <licenses>
      <license>
        <name>GPL3</name>
        <url>https://www.gnu.org/licenses/gpl-3.0.en.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:mcsherrylabs/sss-events.git</url>
      <connection>scm:git:git@github.com:mcsherrylabs/sss-events.git</connection>
    </scm>
    <developers>
      <developer>
        <id>mcsherrylabs</id>
        <name>Alan McSherry</name>
        <url>http://mcsherrylabs.com</url>
      </developer>
    </developers>)
)

// Core library project (implicit root)
lazy val core = (project in file("."))
  .settings(publishingSettings)
  .settings(
    name := "sss-events",
    version := "0.0.9",
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
