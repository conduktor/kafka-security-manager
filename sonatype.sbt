// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "io.github.conduktor"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// License of your choice
licenses ++= Seq("MIT" -> url("https://github.com/conduktor/kafka-security-manager/blob/master/LICENSE.txt"))

// Where is the source code hosted
import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("conduktor", "kafka-security-manager", "conduktor@users.noreply.github.com"))

developers := List(Developer(id="conduktor",name="Stephane Maarek",email="conduktor@users.noreply.github.com",url=  url("https://github.com/conduktor")))
sonatypeCredentialHost := "s01.oss.sonatype.org"
