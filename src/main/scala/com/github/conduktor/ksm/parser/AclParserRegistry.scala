package com.github.conduktor.ksm.parser

import com.github.conduktor.ksm.AppConfig

class AclParserRegistry(val appConfig: AppConfig) {

  val csvParser = new CsvAclParser(appConfig.Parser.csvDelimiter)
  val yamlParser = new YamlAclParser()

  val parserMap: Map[String, AclParser] = Map(
    csvParser.name -> csvParser,
    yamlParser.name -> yamlParser
  )

  def getParser(parserName: String): AclParser = {
    parserMap.getOrElse(
      parserName,
      throw new RuntimeException(s"Parse not found for $parserName")
    )
  }

  def getParserByFilename(fileName: String): AclParser = {
    val ext = fileName.split("\\.").last
    parserMap.values
      .find(_.matchesExtension(ext))
      .getOrElse(
        csvParser
        // I avoid throwing to stay backwards compatible, extension was not influencing parsing
        //throw new RuntimeException(s"Parser not found for extension: $ext")
      )
  }
}
