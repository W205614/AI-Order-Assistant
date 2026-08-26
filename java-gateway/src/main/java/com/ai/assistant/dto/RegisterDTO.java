package com.ai.assistant.dto;

import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterDTO {
    private String username;
    private String password;
    private String nickname;
}
