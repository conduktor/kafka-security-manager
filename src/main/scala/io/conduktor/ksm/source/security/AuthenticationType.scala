package io.conduktor.ksm.source.security

object AuthenticationType extends Enumeration {
  type AuthenticationType = Value
  val NONE, GOOGLE_IAM = Value
}
