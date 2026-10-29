package com.auditsphere.remediation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

class KafkaErrorHandlerTest {
  @SuppressWarnings("unchecked")
  @Test
  void invalidRecordsAreQuarantinedAndUnavailableSnapshotsKeepRetrying() {
    var template = mock(KafkaTemplate.class);
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    String dlt = IssueEvent.ISSUED + ".DLT";
    when(template.partitionsFor(dlt))
        .thenReturn(List.of(new PartitionInfo(dlt, 0, null, null, null), new PartitionInfo(dlt, 2, null, null, null)));
    var handler = new KafkaConfig().kafkaErrorHandler(template);
    var consumer = mock(Consumer.class);
    var container = mock(MessageListenerContainer.class);

    assertTrue(
        handler.handleOne(
            new IllegalArgumentException("Invalid issue snapshot"),
            new ConsumerRecord<>(IssueEvent.ISSUED, 2, 7L, "mission", "invalid"),
            consumer,
            container));
    verify(template)
        .send(argThat((ProducerRecord r) -> r.topic().equals(dlt) && r.partition() == 2 && r.value().equals("invalid")));

    clearInvocations(template);
    assertFalse(
        handler.handleOne(
            new IssueSnapshotClient.SnapshotUnavailable("down", null),
            new ConsumerRecord<>(IssueEvent.ISSUED, 2, 8L, "mission", "valid"),
            consumer,
            container));
    verify(template, never()).send(any(ProducerRecord.class));
  }
}
