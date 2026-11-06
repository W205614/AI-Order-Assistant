package com.ai.assistant.integration.support;

import com.ai.assistant.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class PhoneIdentityController {
  private final PhoneIdentityService identities;

  public PhoneIdentityController(PhoneIdentityService identities) {
    this.identities = identities;
  }

  @PostMapping("/auth/phone/challenges")
  public Result<?> challenge(
      @RequestBody PhoneIdentityService.ChallengeInput input, HttpServletRequest request) {
    return Result.success(identities.challenge(input, request.getRemoteAddr()));
  }

  @GetMapping("/auth/phone")
  public Result<?> current() {
    return Result.success(identities.current());
  }

  @PostMapping("/auth/phone")
  public Result<?> bind(@RequestBody PhoneIdentityService.BindInput input) {
    identities.bind(input);
    return Result.success();
  }

  @PostMapping("/auth/password/recovery")
  public Result<?> recover(@RequestBody PhoneIdentityService.RecoveryInput input) {
    identities.recover(input);
    return Result.success();
  }
}
