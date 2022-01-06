ThisBuild / organization := "io.conduktor"
ThisBuild / homepage := Some(url("https://github.com/conduktor/kafka-security-manager"))
ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild /developers := List(
  Developer(
    "conduktor",
    "Stephane Maarek",
    "conduktor@users.noreply.github.com",
    url("https://github.com/conduktor")
  )
)

name := "kafka-security-manager"

version := "1.0.1"

scalaVersion := "2.12.15"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .enablePlugins(ClasspathJarPlugin)
  .enablePlugins(AshScriptPlugin)


resolvers ++= Seq(
  "Artima Maven Repository" at "https://repo.artima.com/releases",
  Resolver.bintrayRepo("beyondthelines", "maven")
)

libraryDependencies ++= Seq(
  // kafka
  "org.apache.kafka" %% "kafka" % "2.8.1",
  "io.github.embeddedkafka" %% "embedded-kafka" % "2.8.1" % "test",
  "io.findify" %% "s3mock" % "0.2.6" % "test",

  //netty
  "io.netty" % "netty-handler" % "4.1.72.Final",

  "org.apache.kafka" % "kafka-clients" % "2.5.0", // needed explicitly for proper classPath
  "org.apache.kafka" % "kafka-clients" % "2.5.0" % Test classifier "test",

  // test
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "org.scalamock" %% "scalamock" % "5.1.0" % Test,
  "com.github.tomakehurst" % "wiremock" % "2.27.2" % Test,

  // logging
  "org.slf4j" % "slf4j-api" % "1.7.32",
  "org.slf4j" % "slf4j-log4j12" % "1.7.32",
  "org.apache.logging.log4j" % "log4j" % "2.17.1",

  // config
  "com.typesafe" % "config" % "1.3.3",

  // parsers
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "io.circe" %% "circe-yaml" % "0.14.1",
  "io.circe" %% "circe-generic" % "0.14.1",

  // APIs
  "org.skinny-framework" %% "skinny-http-client" % "2.3.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.13.1",

  // AWS SDK to access S3
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.385",

  // Google Auth
  "com.google.auth" % "google-auth-library-oauth2-http" % "0.25.5"

)

Compile / mainClass := Some("io.conduktor.ksm.KafkaSecurityManager")

Test / parallelExecution := false

// Docker stuff
dockerRepository := Some("conduktor")
dockerUpdateLatest := true
dockerBaseImage := "openjdk:11-jre-slim"


assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _@_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assembly / assemblyJarName := s"${name.value}-${version.value}.jar"
