resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.3.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.6")

resolvers += "Artima Maven Repository" at "https://repo.artima.com/releases"

addSbtPlugin("com.artima.supersafe" % "sbtplugin" % "1.1.7")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.7")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10")

addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
