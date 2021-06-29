package io.conduktor.ksm.source

import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry

import java.io.{File, FileNotFoundException, FileReader}

class FileSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  override val CONFIG_PREFIX: String = "file"
  final val FILENAME_CONFIG = "filename"

  var filename: String = _
  val modifiedMap: Map[String, Long] = Map[String, Long]()

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
  override def refresh(): List[ParsingContext] = {

    val path = new File(filename)
    if (path.exists()) {
      val files =
        if (path.isFile)
          List(path)
        else
          path.listFiles.filter(_.isFile).toList

      files.map(file =>
        ParsingContext(
          file.getName,
          parserRegistry.getParserByFilename(file.getName),
          new FileReader(file),
          file.lastModified >= (modifiedMap.get(file.getName) match {
            case None =>
              modifiedMap + (file.getName -> file.lastModified)
              0L
            case Some(value) => value
          })
        )
      )
    } else {
      throw new FileNotFoundException(
        s"The provided file does not exist: $filename"
      )
    }
  }

  override def close(): Unit = ()

}
