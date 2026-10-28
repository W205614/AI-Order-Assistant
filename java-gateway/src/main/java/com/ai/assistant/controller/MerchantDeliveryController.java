package com.ai.assistant.controller;

import static com.ai.assistant.service.CommerceTypes.*;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

@RestController
public class MerchantDeliveryController {
  private final MerchantDeliveryService delivery;
  public MerchantDeliveryController(MerchantDeliveryService delivery) { this.delivery = delivery; }

  @PutMapping("/admin/home-campus/{id}")
  public Result<?> home(@PathVariable long id) {
    delivery.homeCampus(id);
    return Result.success(null);
  }

  @GetMapping("/admin/home-campus")
  public Result<?> home() {
    return Result.success(delivery.homeCampus());
  }

  @GetMapping("/admin/delivery-rules")
  public Result<?> rules() {
    return Result.success(delivery.rules());
  }

  @PutMapping("/admin/delivery-rules")
  public Result<?> rule(@RequestBody RuleInput x) {
    delivery.rule(x);
    return Result.success(null);
  }

}
