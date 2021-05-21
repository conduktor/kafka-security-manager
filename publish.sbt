ThisBuild / organization := "io.github.conduktor"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/conduktor/kafka-security-manager"),
    "scm:git@github.com:conduktor/kafka-security-manager.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id    = "Your identifier",
    name  = "Your Name",
    email = "your@email",
    url   = url("http://your.url")
  )
)

ThisBuild / description := "Some description about your project."
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/example/project"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
