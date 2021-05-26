package io.conduktor.ksm.parser

/**
  * Wrapper to exceptions in order to keep data of the row that failed
  *
  * @param t exception that has been thrown
  */
class ParserException(t: Throwable) extends RuntimeException(t) {}
