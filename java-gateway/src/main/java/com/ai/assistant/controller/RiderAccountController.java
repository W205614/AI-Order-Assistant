package com.ai.assistant.controller;

import static com.ai.assistant.service.CommerceTypes.*;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

@RestController
public class RiderAccountController {
  private final RiderAccountService riders;
  public RiderAccountController(RiderAccountService riders) { this.riders = riders; }

  @GetMapping("/platform/riders")
  public Result<?> riders() {
    return Result.success(riders.list());
  }

  @PostMapping("/platform/riders")
  public Result<?> rider(@RequestBody RiderInput x) {
    return Result.success(riders.rider(x));
  }

}
