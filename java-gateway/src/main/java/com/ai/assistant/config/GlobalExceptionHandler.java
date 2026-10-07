package com.ai.assistant.config;

import com.ai.assistant.security.AuthException;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
  @ExceptionHandler(
      org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  public void disconnected() {
    /* The SSE client closed an already committed response. */
  }

  private ResponseEntity<Result<Object>> error(
      HttpStatus status, String code, String msg, Object data) {
    Result<Object> result = Result.error(msg);
    result.setErrorCode(code);
    result.setData(data);
    return ResponseEntity.status(status).body(result);
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException e) {
    return error(e.status(), e.errorCode(), e.getMessage(), e.data());
  }

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<?> auth(AuthException e) {
    return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    var field = e.getBindingResult().getFieldError();
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_ARGUMENT",
        field == null ? "参数无效" : field.getDefaultMessage(),
        null);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_ARGUMENT",
        e instanceof IllegalArgumentException ? e.getMessage() : "请求参数缺失或格式错误",
        null);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<?> duplicate(DuplicateKeyException e) {
    return error(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "资源已存在或操作已提交，请刷新", null);
  }

  @ExceptionHandler(CannotAcquireLockException.class)
  public ResponseEntity<?> locked(CannotAcquireLockException e) {
    return error(HttpStatus.CONFLICT, "CONCURRENT_OPERATION", "存在并发操作，请刷新后重试", null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> missing(NoResourceFoundException e) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在", null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> method(HttpRequestMethodNotSupportedException e) {
    return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支持该请求方法", null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    log.error("Request failed category={}", e.getClass().getSimpleName());
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统繁忙，请稍后重试", null);
  }
}
