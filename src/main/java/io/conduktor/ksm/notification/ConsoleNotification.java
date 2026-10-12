package io.conduktor.ksm.notification;

import com.typesafe.config.Config;
import io.conduktor.ksm.ReconciliationPlan;
import io.conduktor.ksm.parser.Acls;
import org.slf4j.*;

public final class ConsoleNotification implements Notification {
  private static final Logger LOG = LoggerFactory.getLogger(ConsoleNotification.class);

  public String configPrefix() {
    return "console";
  }

  public void configure(Config config) {}

  public void success(ReconciliationPlan plan) {
    plan.added().stream().sorted(Acls.ORDER).forEach(a -> LOG.info("ADDED {}", a));
    plan.removed().stream().sorted(Acls.ORDER).forEach(a -> LOG.info("REMOVED {}", a));
  }

  public void failure(String description) {
    LOG.error("{}", description);
  }
}
