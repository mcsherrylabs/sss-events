import sbt.Keys.resolvers

publishMavenStyle := true

updateOptions := updateOptions.value.withGigahorse(false)

organization := "com.mcsherrylabs"

pomIncludeRepository := { _ => false }


publishTo := Some {
  val sonaUrl = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    "snapshots" at sonaUrl + "content/repositories/snapshots"
  else
    "releases" at sonaUrl + "service/local/staging/deploy/maven2"
}

credentials += sys.env.get("SONA_USER").map(userName => Credentials(
  "Sonatype Nexus Repository Manager",
  "oss.sonatype.org",
  userName,
  sys.env.getOrElse("SONA_PASS", ""))
).getOrElse(
  Credentials(Path.userHome / ".ivy2" / ".credentials")
)

Test / publishArtifact := false

usePgpKeyHex("F4ED23D42A612E27F11A6B5AF75482A04B0D9486")

javacOptions := Seq("-source", "11", "-target", "11")

name := "sss-events"

version := "0.0.8"

scalaVersion := "2.13.12"


libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.2" % Test

//libraryDependencies += "com.typesafe" % "config" % "1.4.2"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"

libraryDependencies += "ch.qos.logback" % "logback-core" % "1.4.4" % Test

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.4" % Test

resolvers += "stepsoft" at "https://nexus.mcsherrylabs.com/repository/releases"

resolvers += "stepsoft-snapshots" at "https://nexus.mcsherrylabs.com/repository/snapshots"



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
