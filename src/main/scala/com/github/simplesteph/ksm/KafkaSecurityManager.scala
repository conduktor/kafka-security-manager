package com.github.simplesteph.ksm

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

import org.slf4j.LoggerFactory

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val aclSynchronizer = new AclSynchronizer
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
