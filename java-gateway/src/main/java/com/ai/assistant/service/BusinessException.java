package com.ai.assistant.service;

import org.springframework.http.HttpStatus;

/** A public, safe failure with its HTTP semantics and optional canonical state. */
public class BusinessException extends IllegalArgumentException {
  private final HttpStatus status;
  private final String errorCode;
  private final Object data;

  public BusinessException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  public BusinessException(HttpStatus status, String code, String message, Object data) {
    super(message);
    this.status = status;
    this.errorCode = code;
    this.data = data;
  }

  public HttpStatus status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }

  public Object data() {
    return data;
  }

  public static BusinessException conflict(String message, Object data) {
    return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", message, data);
  }
}
