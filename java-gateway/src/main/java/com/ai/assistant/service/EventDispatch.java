package com.ai.assistant.service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared bounded I/O lane. Scheduler threads only signal, never perform network writes. */
@Component
public class EventDispatch {
  private final ThreadPoolExecutor executor;

  public EventDispatch(
      @Value("${events.sender-threads:4}") int threads,
      @Value("${events.queue-capacity:200}") int capacity) {
    executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity), r -> {
          var t = new Thread(r, "event-sender");
          t.setDaemon(true);
          return t;
        }, new ThreadPoolExecutor.AbortPolicy());
  }

  public void submit(AtomicBoolean busy, Runnable task, Runnable rejected) {
    if (!busy.compareAndSet(false, true)) return;
    try {
      executor.execute(() -> {
        try { task.run(); } finally { busy.set(false); }
      });
    } catch (RejectedExecutionException e) {
      busy.set(false);
      rejected.run();
    }
  }

  public int queued() { return executor.getQueue().size(); }

  @jakarta.annotation.PreDestroy
  public void stop() { executor.shutdownNow(); }
}
