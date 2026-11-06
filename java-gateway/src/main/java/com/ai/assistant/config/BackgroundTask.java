package com.ai.assistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Correlates durable task IDs without recording provider payloads or customer data. */
public final class BackgroundTask {
  private static final Logger LOG = LoggerFactory.getLogger(BackgroundTask.class);

  private BackgroundTask() {}

  public static void run(String taskId, Runnable work) {
    String request = MDC.get("requestId");
    String previousTask = MDC.get("taskId");
    if (request == null) MDC.put("requestId", "task-" + taskId);
    MDC.put("taskId", taskId);
    try {
      work.run();
      LOG.info("Background task completed");
    } catch (RuntimeException failure) {
      LOG.warn("Background task failed: {}", failure.getClass().getSimpleName());
      throw failure;
    } finally {
      if (request == null) MDC.remove("requestId");
      if (previousTask == null) MDC.remove("taskId");
      else MDC.put("taskId", previousTask);
    }
  }
}
