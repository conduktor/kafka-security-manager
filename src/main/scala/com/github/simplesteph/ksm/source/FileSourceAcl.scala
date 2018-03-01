package com.github.simplesteph.ksm.source

import java.io.{ File, FileReader }

import com.github.simplesteph.ksm.parser.CsvParser

class FileSourceAcl(filename: String) extends SourceAcl {

  var lastModified: Long = -1

  /**
   * We use the metadata of the file (last modified date)
   * to determine if there are changes to it.
   *
   * Uses a CSV parser on the file afterwards
   * @return
   */
  override def refresh(): Option[SourceAclResult] = {
    val file = new File(filename)
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
