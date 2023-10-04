package io.conduktor.ksm.source.security

trait HttpAuthentication {

  def authHeaderKey: String

  def authHeaderValue: String

}

