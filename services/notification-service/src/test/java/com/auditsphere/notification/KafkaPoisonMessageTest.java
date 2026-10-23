package com.auditsphere.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

class KafkaPoisonMessageTest {
  @SuppressWarnings("unchecked")
  @Test
  void invalidRecordIsPublishedBeforeRecoveryAndDatabaseErrorsKeepRetrying() {
    var template = mock(KafkaTemplate.class);
    var consumer = mock(Consumer.class);
    var container = mock(MessageListenerContainer.class);
    when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    when(template.partitionsFor("audit.mission.assigned.v1.DLT")).thenReturn(java.util.List.of(
        new org.apache.kafka.common.PartitionInfo("audit.mission.assigned.v1.DLT", 0, null, null, null),
        new org.apache.kafka.common.PartitionInfo("audit.mission.assigned.v1.DLT", 1, null, null, null)));
    var handler = new KafkaConfig().kafkaErrorHandler(template);
    var record = new ConsumerRecord<>("audit.mission.assigned.v1", 1, 42L, "key", "invalid");
    assertTrue(handler.handleOne(new IllegalArgumentException("invalid"), record, consumer, container));
    verify(template).send(argThat((ProducerRecord r) -> r.topic().equals("audit.mission.assigned.v1.DLT") && r.partition() == 1 && r.value().equals("invalid")));
    clearInvocations(template);
    assertFalse(handler.handleOne(new org.springframework.dao.DataAccessResourceFailureException("offline"),
        new ConsumerRecord<>("audit.mission.assigned.v1", 1, 43L, "key", "valid"), consumer, container));
    verify(template, never()).send(any(ProducerRecord.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void failedQuarantineDoesNotRecoverRecord() {
    var template = mock(KafkaTemplate.class);
    when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    when(template.partitionsFor("audit.mission.assigned.v1.DLT")).thenReturn(java.util.List.of(
        new org.apache.kafka.common.PartitionInfo("audit.mission.assigned.v1.DLT", 0, null, null, null),
        new org.apache.kafka.common.PartitionInfo("audit.mission.assigned.v1.DLT", 1, null, null, null)));
    var handler = new KafkaConfig().kafkaErrorHandler(template);
    assertFalse(handler.handleOne(new IllegalArgumentException("invalid"),
        new ConsumerRecord<>("audit.mission.assigned.v1", 0, 1L, "key", "invalid"),
        mock(Consumer.class), mock(MessageListenerContainer.class)));
  }
  @Test
  void nullPayloadsAreClassifiedAsInvalidInsteadOfRetriedForever() {
    var consumer = new AssignmentConsumer(mock(org.springframework.jdbc.core.JdbcTemplate.class),
        new com.fasterxml.jackson.databind.ObjectMapper(), mock(NotificationStream.class));
    assertThrows(IllegalArgumentException.class, () -> consumer.receive(null));
    assertThrows(IllegalArgumentException.class, () -> consumer.receive("null"));
  }
}
