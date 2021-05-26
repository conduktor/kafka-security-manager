package io.conduktor.ksm.source

import io.conduktor.ksm.TestFixtures._
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.parser.csv.CsvAclParser

import java.io.StringReader

class DummySourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  var noneNext = false
  var errorNext = false
  val csvAclParser: CsvAclParser = new CsvAclParser()

  // initial state
  val sar1 = Set(
    res1 -> acl1,
    res1 -> acl2,
    res2 -> acl3
  )

  // one deletion, one add
  val sar2 = Set(
    res1 -> acl1,
    res2 -> acl3,
    res3 -> acl2
  )

  // all gone
  val sar3 = Set()

  // all state changes
  val sars = List(sar1, sar2, sar3)
  // a states iterator, shifting its position changes current state
  private val sarsIterator = sars.iterator

  override def refresh(): Option[ParsingContext] = {
    if (noneNext) {
      noneNext = false
      None
    } else if (errorNext) {
      errorNext = false
      throw new RuntimeException("triggered error")
    } else {
      Some(
        ParsingContext(
          csvAclParser,
          new StringReader(csvAclParser.formatAcls(sarsIterator.next().toList))
        )
      )
    }
  }

  def setNoneNext(): Unit = {
    noneNext = true
  }

  def setErrorNext(): Unit = {
    errorNext = true
  }

  override def close(): Unit = ()

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "dummy"

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = ()
}
