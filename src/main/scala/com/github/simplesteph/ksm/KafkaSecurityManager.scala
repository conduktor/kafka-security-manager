package com.github.simplesteph.ksm

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

import com.github.simplesteph.ksm.notification.ConsoleNotification
import com.github.simplesteph.ksm.source.FileSourceAcl
import kafka.security.auth.SimpleAclAuthorizer
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)


  private val simpleAclAuthorizer: SimpleAclAuthorizer = new SimpleAclAuthorizer
  private val configs = Map(
    "zookeeper.connect" -> "localhost:2181",
  )
  simpleAclAuthorizer.configure(configs.asJava)

  private val sourceAcl = new FileSourceAcl("example/acls.csv")
  private val notification = ConsoleNotification

  val aclSynchronizer = new AclSynchronizer(
    simpleAclAuthorizer,
    sourceAcl,
    notification
  )


  val executor = Executors.newScheduledThreadPool(1)
  val f: ScheduledFuture[_] = executor.scheduleAtFixedRate(aclSynchronizer, 1, 10, TimeUnit.SECONDS)

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      aclSynchronizer.close()
      log.info("Kafka Security Manager is shutting down...")
      f.cancel(false)
      log.info("Waiting for thread to cleanly shutdown (10 seconds maximum)")
      executor.shutdown()
      executor.awaitTermination(10, TimeUnit.SECONDS)
      log.info("Kafka Security Manager has shut down...")
    }
  })
}
