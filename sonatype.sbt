// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "com.github.simplesteph"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// License of your choice
licenses ++= Seq("MIT" -> url("https://github.com/simplesteph/kafka-security-manager/blob/master/LICENSE.txt"))

// Where is the source code hosted
import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("simplesteph", "kafka-security-manager", "simplesteph@users.noreply.github.com"))

developers := List(Developer(id="simplesteph",name="Stephane Maarek",email="simplesteph@users.noreply.github.com",url=  url("https://github.com/simplesteph")))

credentials ++= (for {
  username <- Option(System.getenv().get("SONATYPE_USERNAME"))
  password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
} yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq


pgpPublicRing := file("./travis/local.pubring.asc")
pgpSecretRing := file("./travis/local.secring.asc")
pgpPassphrase := Option(System.getenv().get("PGP_PASS").toCharArray)