package io.conduktor.ksm.source

import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

import sys.process._
import java.io._

class ExecSourceAcl(parserRegistry: AclParserRegistry) extends SourceAcl(parserRegistry) {

    private val log = LoggerFactory.getLogger(classOf[ExecSourceAcl])
    
    override val CONFIG_PREFIX: String        = "exec"
    final val COMMAND_CONFIG: String          = "command"
    final val COMMAND_ARGS_CONFIG: String     = "args"
    final val COMMAND_ARGS_SEP_CONFIG: String = "sep"
    final val PARSER                          = "parser"

    var command: List[String] = _ 
    var parser: String = "csv"

    /**
    * internal config definition for the module
    */
    override def configure(config: Config): Unit = {
        //The command option should be required
        val cmd = config.getString(COMMAND_CONFIG)
        
        //For args we could use some defaults
        val args: String = if (!config.hasPath(COMMAND_ARGS_CONFIG)) "" else config.getString(COMMAND_ARGS_CONFIG)
        val sep: String = if (!config.hasPath(COMMAND_ARGS_SEP_CONFIG)) "," else config.getString(COMMAND_ARGS_SEP_CONFIG)

        val parserType = if (!config.hasPath(PARSER)) "yaml" else config.getString(PARSER)
        configure(cmd, args, sep, parserType)

    }

    def configure(command: String, args: String, sep: String, parser: String): Unit = {
        
        this.command = List.concat(
            List(command),
            args.split(Pattern.quote(sep))
        )
        log.info("command: {}", this.command)

        this.parser = parser
        log.info("PARSER: {}", this.parser)
    }

    def configure(command: String, args: String, sep: String): Unit = {

        configure(command, args, sep, this.parser)

    }

    override def refresh(): Option[ParsingContext] = {
        val (return_code, stdout, stderr) = exec(command)

        // If return_code is 0, the command was a success, parse out the stdout
        return_code match {
            case 0 => 
                Some(
                    ParsingContext(
                      parserRegistry.getParser(this.parser),
                      new StringReader(stdout)
                    )
                )
            // Otherwise, assume something went wrong
            case _ => {
                log.error("Error executing the process, got return code {}", return_code)
                log.debug("Stdout: {}", stdout)
                log.error("Stderr: {}", stderr)
                None
            }
        }
    }

    /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
    override def close(): Unit = {
        // Do nothing
    }

    //Function here is taken from a StackOverflow answer I found
    private def exec(cmd: Seq[String]): (Int, String, String) = {
        val stdoutStream = new ByteArrayOutputStream
        val stderrStream = new ByteArrayOutputStream
        val stdoutWriter = new PrintWriter(stdoutStream)
        val stderrWriter = new PrintWriter(stderrStream)
        val exitValue = cmd.!(ProcessLogger(stdoutWriter.println, stderrWriter.println))
        stdoutWriter.close()
        stderrWriter.close()
        (exitValue, stdoutStream.toString, stderrStream.toString)
    }
  
}
