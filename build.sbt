name := "kafka-security-manager"

organization := "com.github.simplesteph.ksm"

version := "0.1"

scalaVersion := "2.12.4"


libraryDependencies ++= Seq(
  "org.apache.kafka" %% "kafka" % "1.0.0",
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "com.typesafe" % "config" % "1.3.1",
  "org.skinny-framework" %% "skinny-http-client" % "2.3.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.9.4",
  "net.manub" %% "scalatest-embedded-kafka" % "1.0.0" % "test",

    // https://mvnrepository.com/artifact/org.slf4j/slf4j-api
  "org.slf4j" % "slf4j-api" % "1.7.25",
  // https://mvnrepository.com/artifact/org.slf4j/slf4j-simple
  "org.slf4j" % "slf4j-log4j12" % "1.7.25",

)


mainClass in Compile := Some("com.github.simplesteph.ksm.KafkaSecurityManager")
