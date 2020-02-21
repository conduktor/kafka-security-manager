package com.github.simplesteph.ksm.parser

/**
  * Wrapper to exceptions in order to keep data of the row that failed
  * @param t exception that has been thrown
  */
class YamlParserException(error: String, t: Throwable) extends RuntimeException(t) {

  def print(): String = { error }
}
