package com.ai.assistant.controller;

import com.ai.assistant.dto.LoginDTO;
import com.ai.assistant.dto.RegisterDTO;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册 / 登录（公开接口，无需鉴权）
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result register(@RequestBody RegisterDTO dto) {
        return Result.success(authService.register(dto.getUsername(), dto.getPassword(), dto.getNickname()));
    }

    @PostMapping("/login")
    public Result login(@RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto.getUsername(), dto.getPassword()));
    }
}
