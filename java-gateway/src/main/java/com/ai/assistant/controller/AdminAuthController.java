package com.ai.assistant.controller;

import com.ai.assistant.dto.LoginDTO;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.service.AuthRateService;
import com.ai.assistant.vo.Result;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminAuthController {
  private final AuthService auth;
  private final AuthController cookies;
  private final AuthRateService rate;

  public AdminAuthController(AuthService auth, AuthController cookies, AuthRateService rate) {
    this.auth = auth;
    this.cookies = cookies;
    this.rate = rate;
  }

  @PostMapping("/admin/login")
  public Result<Map<String, Object>> login(
      @RequestBody LoginDTO dto, HttpServletRequest req, HttpServletResponse res) {
    rate.check("staff-login", req.getRemoteAddr(), Objects.toString(dto.getUsername(), ""));
    return Result.success(
        cookies.session(auth.adminLogin(dto.getUsername(), dto.getPassword()), true, res));
  }
}
