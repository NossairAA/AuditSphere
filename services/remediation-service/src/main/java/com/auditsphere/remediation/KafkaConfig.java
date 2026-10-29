package com.auditsphere.remediation;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration
class KafkaConfig {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template, (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    // Topics are provisioned with matching partition counts. Never silently reroute.
    recoverer.setVerifyPartition(false);
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
    // audit-service or the database may be down for a while: retry without limit, one minute at most.
    var backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxInterval(60000L);
    var handler = new DefaultErrorHandler(recoverer, backOff);
    // Retries are otherwise silent. Only coordinates are logged, never the payload.
    handler.setRetryListeners(
        (record, error, attempt) ->
            LoggerFactory.getLogger(KafkaConfig.class)
                .warn(
                    "Retrying {}-{}@{}, attempt {}: {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    attempt,
                    // The listener failure wraps the exception thrown by the consumer.
                    (error.getCause() != null ? error.getCause() : error).getClass().getSimpleName()));
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, JacksonException.class, DeserializationException.class);
    return handler;
  }
}
