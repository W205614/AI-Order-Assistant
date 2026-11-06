package com.ai.assistant.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.Map;

public interface PaymentProvider {
  JSONObject request(String method, String path, JSONObject body);

  JSONObject notification(Map<String, String> headers, String body);

  String sign(String message);

  byte[] download(String url);
}
