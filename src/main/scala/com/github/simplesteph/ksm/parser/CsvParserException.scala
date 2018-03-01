package com.github.simplesteph.ksm.parser

class CsvParserException(row: Map[String, String], t: Throwable) extends RuntimeException(t) {

  def printRow(): String = {
    row.toString()
  }
}
