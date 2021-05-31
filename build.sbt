inThisBuild(List(
  organization := "io.conduktor",
  homepage := Some(url("https://github.com/conduktor/kafka-security-manager")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "conduktor",
      "Stephane Maarek",
      "conduktor@users.noreply.github.com",
      url("https://github.com/conduktor")
    )
  )
))


name := "kafka-security-manager"


version := "0.11"

scalaVersion := "2.12.8"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .enablePlugins(ClasspathJarPlugin)


resolvers ++= Seq(
  "Artima Maven Repository" at "https://repo.artima.com/releases",
  Resolver.bintrayRepo("beyondthelines", "maven")
)

libraryDependencies ++= Seq(
  // kafka
  "org.apache.kafka" %% "kafka" % "2.5.0",
  "io.github.embeddedkafka" %% "embedded-kafka" % "2.5.0" % "test",
  "io.findify" %% "s3mock" % "0.2.6" % "test",

  "org.apache.kafka" % "kafka-clients" % "2.5.0", // needed explicitly for proper classPath
  "org.apache.kafka" % "kafka-clients" % "2.5.0" % Test classifier "test",

  // test
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "org.scalamock" %% "scalamock" % "5.1.0" % Test,

  // logging
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "org.slf4j" % "slf4j-log4j12" % "1.7.25",

  // config
  "com.typesafe" % "config" % "1.3.3",

  // parsers
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "io.circe" %% "circe-yaml" % "0.12.0",
  "io.circe" %% "circe-generic" % "0.12.0",

  // APIs
  "org.skinny-framework" %% "skinny-http-client" % "2.3.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.9.4",

  // AWS SDK to access S3
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.385"

)

mainClass in Compile := Some("io.conduktor.ksm.KafkaSecurityManager")

parallelExecution in Test := false

// Docker stuff
dockerRepository := Some("conduktor")
dockerUpdateLatest := true
dockerBaseImage := "openjdk:8-jre-slim"



assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _ @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assemblyJarName in assembly := s"${name.value}-${version.value}.jar"
