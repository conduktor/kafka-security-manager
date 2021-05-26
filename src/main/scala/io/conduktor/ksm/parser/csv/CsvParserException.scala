package io.conduktor.ksm.parser.csv

import io.conduktor.ksm.parser.ParserException

/**
  * Wrapper to exceptions in order to keep data of the row that failed
 *
  * @param row row that triggered the failure (easier for debugging)
  * @param t exception that has been thrown
  */
class CsvParserException(row: Map[String, String], t: Throwable)
    extends ParserException(t) {

  def printRow(): String = {
    row.toString()
  }
}
