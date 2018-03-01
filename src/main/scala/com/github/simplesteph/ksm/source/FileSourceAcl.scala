package com.github.simplesteph.ksm.source

import java.io.{File, FileReader}

import com.github.simplesteph.ksm.parser.CsvParser
import kafka.security.auth._

import scala.util.Try

class FileSourceAcl extends SourceAcl  {

  var lastModified: Long = -1

  override def refreshSourceAcl(): Option[SourceAclResult] = {
    val file = new File("example/acls.csv")
    if (file.lastModified() > lastModified) {
      val reader = new FileReader(file)
      val res = CsvParser.aclsFromCsv(reader)
      reader.close()
      lastModified = file.lastModified()
      Some(res)
    } else {
      None
    }
  }

  override def close(): Unit = ()
}
