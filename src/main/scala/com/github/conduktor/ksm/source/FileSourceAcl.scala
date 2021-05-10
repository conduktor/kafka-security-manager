package com.github.conduktor.ksm.source

import com.github.conduktor.ksm.parser.{AclParser, AclParserRegistry}

import java.io.{File, FileReader, Reader}
import com.typesafe.config.Config

class FileSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

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
  override def refresh(): Option[(AclParser, Reader)] = {
    val file = new File(filename)
    if (file.lastModified() > lastModified) {
      val reader = new FileReader(file)
      lastModified = file.lastModified()
      Some((parserRegistry.getParserByFilename(filename), reader))
    } else {
      None
    }
  }

  override def close(): Unit = ()

}
