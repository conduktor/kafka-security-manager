package com.github.simplesteph.ksm.parser

/**
 * Wrapper to exceptions in order to keep data of the row that failed
 * @param row row that triggered the failure (easier for debugging)
 * @param t exception that has been thrown
 */
class CsvParserException(row: Map[String, String], t: Throwable) extends RuntimeException(t) {

  def printRow(): String = {
    row.toString()
  }
}
