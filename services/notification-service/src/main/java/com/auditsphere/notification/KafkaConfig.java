package com.auditsphere.notification;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {
  @Bean
  DefaultErrorHandler kafkaErrorHandler() {
    var handler = new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    // Never silently acknowledge malformed messages or database failures.
    handler.setClassifications(java.util.Map.of(Exception.class, true), true);
    return handler;
  }
}
