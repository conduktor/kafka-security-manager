ThisBuild / organization := "io.github.conduktor"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/conduktor/kafka-security-manager"),
    "scm:git@github.com:conduktor/kafka-security-manager.git"
  )
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
