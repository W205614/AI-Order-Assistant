package com.ai.assistant.controller;

import static com.ai.assistant.service.CommerceTypes.*;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

@RestController
public class MerchantDeliveryController {
  private final MerchantDeliveryService delivery;

  public MerchantDeliveryController(MerchantDeliveryService delivery) {
    this.delivery = delivery;
  }

  @PutMapping("/admin/home-campus/{id}")
  public Result<?> home(@PathVariable long id) {
    delivery.homeCampus(id);
    return Result.success(null);
  }

  @GetMapping("/admin/home-campus")
  public Result<?> home() {
    return Result.success(delivery.homeCampus());
  }

  public record PreparationInput(Integer min, Integer max) {}

  @PutMapping("/admin/preparation-range")
  public Result<?> preparation(@RequestBody PreparationInput input) {
    if (input.min() == null || input.max() == null)
      throw new IllegalArgumentException("请填写最短与最长备餐时间");
    delivery.preparation(input.min(), input.max());
    return Result.success();
  }

  public record PickupPointInput(String pickupPoint) {}

  @PutMapping("/admin/pickup-point")
  public Result<?> pickupPoint(@RequestBody PickupPointInput input) {
    delivery.pickupPoint(input.pickupPoint());
    return Result.success(null);
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
