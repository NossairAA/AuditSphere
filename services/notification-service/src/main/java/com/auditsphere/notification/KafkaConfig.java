package com.auditsphere.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
        (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    // Topics are provisioned with matching partition counts. Never silently reroute.
    recoverer.setVerifyPartition(false);
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
    var handler = new DefaultErrorHandler(recoverer,
        new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.addNotRetryableExceptions(IllegalArgumentException.class,
        JsonProcessingException.class, DeserializationException.class);
    return handler;
  }
}
