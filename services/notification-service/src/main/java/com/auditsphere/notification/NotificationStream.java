package com.auditsphere.notification;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class NotificationStream {
  @org.springframework.beans.factory.annotation.Value("${notifications.stream.max-connections:200}")
  private int maxConnections = 200;

  private final Map<String, Set<SseEmitter>> subscribers = new HashMap<>();
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor sender =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(200),
          r -> {
            var t = new Thread(r, "notification-stream");
            t.setDaemon(true);
            return t;
          });

  synchronized SseEmitter subscribe(String subject, Instant expiry) {
    long timeout = Math.min(25000, expiry.toEpochMilli() - System.currentTimeMillis());
    if (timeout <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (subscribers.values().stream().mapToInt(Set::size).sum() >= maxConnections
        || subscribers.getOrDefault(subject, Set.of()).size() >= 3)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    var emitter = new SseEmitter(timeout);
    subscribers.computeIfAbsent(subject, key -> new HashSet<>()).add(emitter);
    Runnable remove = () -> remove(subject, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(
        () -> {
          remove.run();
          emitter.complete();
        });
    emitter.onError(error -> remove.run());
    try {
      // Reconnects always refresh persisted state, including events missed while disconnected.
      emitter.send(SseEmitter.event().data("refresh").reconnectTime(3000));
    } catch (Exception e) {
      remove.run();
      emitter.complete();
    }
    return emitter;
  }

  private synchronized void remove(String subject, SseEmitter emitter) {
    var group = subscribers.get(subject);
    if (group != null) {
      group.remove(emitter);
      if (group.isEmpty()) subscribers.remove(subject);
    }
  }

  private synchronized List<SseEmitter> targets(String subject) {
    return List.copyOf(subscribers.getOrDefault(subject, Set.of()));
  }

  void changed(String subject) {
    if (targets(subject).isEmpty() || !pending.add(subject)) return;
    try {
      sender.execute(
          () -> {
            pending.remove(subject);
            for (var emitter : targets(subject)) {
              try {
                emitter.send(SseEmitter.event().data("refresh"));
              } catch (Exception e) {
                remove(subject, emitter);
                emitter.complete();
              }
            }
          });
    } catch (RejectedExecutionException full) {
      // Persisted notifications remain available through reconnect and periodic reconciliation.
      pending.remove(subject);
    }
  }

  @jakarta.annotation.PreDestroy
  void close() {
    sender.shutdownNow();
  }
}
