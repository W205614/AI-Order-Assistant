package com.ai.assistant.config;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class BackgroundTaskTest {
  private static final class CapturingAppender extends ListAppender<ILoggingEvent> {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  }

  @Test
  void taskLogCorrelatesTheDurableIdAndCleansTheWorkerThread() {
    var logger = (Logger) LoggerFactory.getLogger(BackgroundTask.class);
    var events = new CapturingAppender();
    events.start();
    logger.addAppender(events);
    try {
      BackgroundTask.run("refund-123", () -> assertEquals("task-refund-123", MDC.get("requestId")));
      assertEquals("refund-123", events.list.getLast().getMDCPropertyMap().get("taskId"));
      assertEquals("task-refund-123", events.list.getLast().getMDCPropertyMap().get("requestId"));
      assertNull(MDC.get("requestId"));
      assertNull(MDC.get("taskId"));
    } finally {
      logger.detachAppender(events);
      events.stop();
      MDC.clear();
    }
  }

  @Test
  void preservesTheHttpTraceAndDoesNotLogSensitiveExceptionMessages() {
    var logger = (Logger) LoggerFactory.getLogger(BackgroundTask.class);
    var events = new CapturingAppender();
    events.start();
    logger.addAppender(events);
    MDC.setContextMap(Map.of("requestId", "http-test-request", "taskId", "parent-task"));
    var failure = new IllegalStateException("sensitive-provider-payload");
    try {
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  BackgroundTask.run(
                      "payment-123",
                      () -> {
                        throw failure;
                      })));
      var event = events.list.getLast();
      assertEquals("http-test-request", event.getMDCPropertyMap().get("requestId"));
      assertEquals("payment-123", event.getMDCPropertyMap().get("taskId"));
      assertFalse(event.getFormattedMessage().contains("sensitive-provider-payload"));
      assertNull(event.getThrowableProxy());
      assertEquals("http-test-request", MDC.get("requestId"));
      assertEquals("parent-task", MDC.get("taskId"));
    } finally {
      logger.detachAppender(events);
      events.stop();
      MDC.clear();
    }
  }
}
