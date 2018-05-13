package com.github.simplesteph.ksm.notification

import com.github.simplesteph.ksm.parser.CsvParserException

import scala.util.Try

object NotificationUtils {

  def errorsToString(errs: List[Try[Throwable]]): List[String] = {
    errs.map(e =>
      e.get match {
        case cPE: CsvParserException =>
          s"${cPE.getLocalizedMessage} | Row: ${cPE.printRow()}"
        case _ => s"error while parsing ACL source: ${e.get}"
    })
  }

}
