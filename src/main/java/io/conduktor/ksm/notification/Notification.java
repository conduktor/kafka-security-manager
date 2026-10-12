package io.conduktor.ksm.notification;

import com.typesafe.config.Config;
import io.conduktor.ksm.ReconciliationPlan;

public interface Notification extends AutoCloseable {
  String configPrefix();

  void configure(Config config);

  void success(ReconciliationPlan plan) throws Exception;

  void failure(String description) throws Exception;

  @Override
  default void close() {}
}
