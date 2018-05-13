resolvers ++= Seq(
  Resolver.bintrayRepo("beyondthelines", "maven")
)

addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.18")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.7.4",
  "beyondthelines" %% "grpcgatewaygenerator" % "0.0.9",
)