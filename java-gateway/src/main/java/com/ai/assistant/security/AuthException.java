package com.ai.assistant.security;

/**
 * 鉴权失败异常（未登录 / 登录过期 / 无权限）
 */
public class AuthException extends RuntimeException {
    public AuthException(String msg) {
        super(msg);
    }
}
