package com.ai.assistant.notification;

import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/notifications")
  public Result<?> list(@RequestParam(defaultValue = "9223372036854775807") long before) {
    return Result.success(notifications.list(before));
  }

  @PostMapping("/notifications/{id}/read")
  public Result<?> read(@PathVariable long id) {
    notifications.read(id);
    return Result.success();
  }

  @GetMapping(value = "/notifications/events", produces = "text/event-stream")
  public SseEmitter events(@RequestParam(defaultValue = "0") long after) {
    return notifications.subscribe(after);
  }

  @GetMapping("/platform/notification-tasks")
  public Result<?> tasks(@RequestParam(defaultValue = "9223372036854775807") long before) {
    return Result.success(notifications.tasks(before));
  }

  public record RetryInput(boolean acknowledgeDuplicateRisk) {}

  @PostMapping("/platform/notification-tasks/{id}/retry")
  public Result<?> retry(@PathVariable long id, @RequestBody RetryInput input) {
    notifications.retry(id, input.acknowledgeDuplicateRisk());
    return Result.success();
  }
}
