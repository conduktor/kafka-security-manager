package com.github.conduktor.ksm.parser

import com.github.conduktor.ksm.AppConfig
import com.github.conduktor.ksm.parser.csv.CsvAclParser
import com.github.conduktor.ksm.parser.yaml.YamlAclParser

class AclParserRegistry(val appConfig: AppConfig) {

  val csvParser = new CsvAclParser(Option(appConfig).map(_.Parser).map(_.csvDelimiter).getOrElse(','))
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
      )
  }
}
