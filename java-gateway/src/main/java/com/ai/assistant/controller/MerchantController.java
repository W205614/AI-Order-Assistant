package com.ai.assistant.controller;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MerchantController {
  private final MerchantService merchants;
  private final AuditService audit;

  public MerchantController(MerchantService merchants, AuditService audit) {
    this.merchants = merchants;
    this.audit = audit;
  }

  @GetMapping("/merchants")
  public Result<?> list() {
    return Result.success(merchants.list(false));
  }

  @GetMapping("/merchants/{id}")
  public Result<?> get(@PathVariable long id) {
    return Result.success(merchants.get(id));
  }

  @GetMapping("/admin/merchant")
  public Result<?> settings() {
    UserContext.requireRole("OWNER", "STAFF");
    return Result.success(merchants.get(UserContext.merchantId()));
  }

  @PutMapping("/admin/merchant")
  public Result<?> configure(@RequestBody MerchantService.Settings input) {
    return Result.success(merchants.configure(input));
  }

  @GetMapping("/admin/staff")
  public Result<?> staff() {
    return Result.success(merchants.staff());
  }

  @PostMapping("/admin/staff")
  public Result<?> addStaff(@RequestBody MerchantService.StaffInput input) {
    merchants.addStaff(input);
    return Result.success();
  }

  public record Enabled(boolean enabled) {}

  @PutMapping("/admin/staff/{id}/enabled")
  public Result<?> enableStaff(@PathVariable long id, @RequestBody Enabled input) {
    merchants.enableStaff(id, input.enabled());
    return Result.success();
  }

  public record Password(String password) {}

  @PostMapping("/admin/staff/{id}/password")
  public Result<?> resetStaff(@PathVariable long id, @RequestBody Password input) {
    merchants.resetStaffPassword(id, input.password());
    return Result.success();
  }

  @GetMapping("/platform/merchants")
  public Result<?> platformList() {
    return Result.success(merchants.list(true));
  }

  @PostMapping("/platform/merchants")
  public Result<?> provision(@RequestBody MerchantService.Provision input) {
    return Result.success(merchants.provision(input));
  }

  @PutMapping("/platform/merchants/{id}/enabled")
  public Result<?> enable(@PathVariable long id, @RequestBody Enabled input) {
    return Result.success(merchants.enable(id, input.enabled()));
  }

  @PutMapping("/platform/users/{id}/enabled")
  public Result<?> enableUser(@PathVariable long id, @RequestBody Enabled input) {
    merchants.enableCustomer(id, input.enabled());
    return Result.success();
  }

  @GetMapping("/platform/audit")
  public Result<?> platformAudit(@RequestParam(defaultValue = "9223372036854775807") long before) {
    return Result.success(audit.list(null, before));
  }

  @GetMapping("/admin/audit")
  public Result<?> merchantAudit(@RequestParam(defaultValue = "9223372036854775807") long before) {
    UserContext.requireRole("OWNER");
    return Result.success(audit.list(UserContext.merchantId(), before));
  }
}
