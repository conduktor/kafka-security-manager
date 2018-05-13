package com.github.simplesteph.ksm.source

import java.io.{File, FileReader}

import com.github.simplesteph.ksm.parser.CsvAclParser
import com.typesafe.config.Config

class FileSourceAcl extends SourceAcl {

  override val CONFIG_PREFIX: String = "file"
  final val FILENAME_CONFIG = "filename"

  var lastModified: Long = -1
  var filename: String = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    filename = config.getString(FILENAME_CONFIG)
  }

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
      val res = CsvAclParser.aclsFromReader(reader)
      reader.close()
      lastModified = file.lastModified()
      Some(res)
    } else {
      None
    }
  }

  override def close(): Unit = ()

}
