package io.conduktor.ksm.source

import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source

import java.io.{File, FileReader}

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
  override def refresh(): Option[ParsingContext] = {
    val file = new File(filename)
    if (file.lastModified() > lastModified) {
      val reader = new FileReader(file)
      lastModified = file.lastModified()
      Some(source.ParsingContext(parserRegistry.getParserByFilename(filename), reader))
    } else {
      None
    }
  }

  override def close(): Unit = ()

}
