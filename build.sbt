name := "kafka-security-manager"

organization := "com.github.simplesteph.ksm"

version := "0.1-SNAPSHOT"

scalaVersion := "2.12.4"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)


resolvers += "Artima Maven Repository" at "http://repo.artima.com/releases"

libraryDependencies ++= Seq(
  // kafka
  "org.apache.kafka" %% "kafka" % "1.0.0",
  "net.manub" %% "scalatest-embedded-kafka" % "1.0.0" % "test",

  // logging
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "org.slf4j" % "slf4j-log4j12" % "1.7.25",

  // config
  "com.typesafe" % "config" % "1.3.1",

  // parsers
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",

  // APIs
  "org.skinny-framework" %% "skinny-http-client" % "2.3.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.9.4",
)

mainClass in Compile := Some("com.github.simplesteph.ksm.KafkaSecurityManager")

parallelExecution in Test := false

// Docker stuff
dockerRepository := Some("simplesteph")
