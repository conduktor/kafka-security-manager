name := "kafka-security-manager"

organization := "com.github.simplesteph.ksm"

version := "0.3-SNAPSHOT"

scalaVersion := "2.12.4"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)


resolvers ++= Seq(
  "Artima Maven Repository" at "http://repo.artima.com/releases",
  Resolver.bintrayRepo("beyondthelines", "maven")
)

libraryDependencies ++= Seq(
  // kafka
  "org.apache.kafka" %% "kafka" % "1.1.0",
  "net.manub" %% "scalatest-embedded-kafka" % "1.1.0-kafka1.1-nosr" % "test",

  // logging
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "org.slf4j" % "slf4j-log4j12" % "1.7.25",

  // config
  "com.typesafe" % "config" % "1.3.3",

  // parsers
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",

  // APIs
  "org.skinny-framework" %% "skinny-http-client" % "2.3.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.9.4",

  // GRPC
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "io.grpc" % "grpc-services" % scalapb.compiler.Version.grpcJavaVersion,

  // REST gateway generation
  "beyondthelines" %% "grpcgatewayruntime" % "0.0.9" % "compile,protobuf"

)

mainClass in Compile := Some("com.github.simplesteph.ksm.KafkaSecurityManager")

parallelExecution in Test := false

// Docker stuff
dockerRepository := Some("simplesteph")
dockerUpdateLatest := true

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value,
  // generate Swagger spec files into the `resources/specs`
  grpcgateway.generators.SwaggerGenerator -> (resourceDirectory in Compile).value / "specs",
  // generate the Rest Gateway source code
  grpcgateway.generators.GatewayGenerator -> (sourceManaged in Compile).value
)