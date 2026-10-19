package com.ai.assistant.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Completing an emitter must not make a scheduler wait behind a blocked MVC initial flush. */
class EventEmitter extends SseEmitter {
  EventEmitter() { super(1800000L); }

  boolean tryComplete() {
    if (!writeLock.tryLock()) return false;
    try {
      // complete() flushes the HTTP response in Spring MVC; error completion only dispatches
      // async termination, so even a writable-lock holder cannot block the business scheduler.
      completeWithError(new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
          "通知连接已关闭，请按游标重新连接"));
      return true;
    } catch (Exception ignored) {
      return true;
    } finally { writeLock.unlock(); }
  }
}
