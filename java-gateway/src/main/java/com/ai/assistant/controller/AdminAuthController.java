package com.ai.assistant.controller;

import com.ai.assistant.dto.LoginDTO;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员登录（公开接口）
 */
@RestController
public class AdminAuthController {

    private final AuthService authService;

    public AdminAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/admin/login")
    public Result login(@RequestBody LoginDTO dto) {
        return Result.success(authService.adminLogin(dto.getUsername(), dto.getPassword()));
    }
}
