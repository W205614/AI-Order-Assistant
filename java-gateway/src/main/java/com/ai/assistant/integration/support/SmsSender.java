package com.ai.assistant.integration.support;

import com.ai.assistant.service.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SmsSender {
  private final Client client;
  private final String signName;
  private final String otpTemplate;

  public SmsSender(SupportSettings settings) {
    String provider = settings.value("integration.sms.provider", "SMS_PROVIDER", "disabled");
    if (!provider.equals("aliyun") && !provider.equals("disabled"))
      throw new IllegalStateException("SMS_PROVIDER must be disabled or aliyun");
    if (provider.equals("disabled")) {
      client = null;
      signName = "";
      otpTemplate = "";
      return;
    }
    signName = settings.required("integration.sms.sign-name", "SMS_SIGN_NAME");
    otpTemplate =
        settings.value(
            "integration.sms.otp-template-code",
            "SMS_OTP_TEMPLATE_CODE",
            settings.value("integration.sms.template-code", "SMS_TEMPLATE_CODE", ""));
    if (otpTemplate.isBlank()) throw new IllegalStateException("SMS_OTP_TEMPLATE_CODE is required");
    String endpoint =
        settings.value("integration.sms.endpoint", "SMS_ENDPOINT", "dysmsapi.aliyuncs.com");
    String protocol = settings.value("integration.sms.protocol", "SMS_PROTOCOL", "HTTPS");
    var uri = java.net.URI.create(protocol.toLowerCase(java.util.Locale.ROOT) + "://" + endpoint);
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getPath() == null || uri.getPath().isEmpty())
        || !(protocol.equalsIgnoreCase("HTTPS")
            || (protocol.equalsIgnoreCase("HTTP")
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
      throw new IllegalStateException(
          "SMS_ENDPOINT must be a HTTPS host; HTTP is restricted to local contract servers");
    Config config =
        new Config()
            .setAccessKeyId(settings.required("integration.sms.access-key-id", "SMS_ACCESS_KEY_ID"))
            .setAccessKeySecret(
                settings.required("integration.sms.access-key-secret", "SMS_ACCESS_KEY_SECRET"))
            .setEndpoint(endpoint)
            .setRegionId(
                settings.value("integration.sms.region-id", "SMS_REGION_ID", "cn-hangzhou"))
            .setProtocol(protocol)
            .setConnectTimeout(3000)
            .setReadTimeout(10000);
    try {
      client = new Client(config);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize Aliyun SMS client", e);
    }
  }

  public boolean enabled() {
    return client != null;
  }

  public void requireEnabled() {
    if (!enabled())
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_DISABLED", "短信服务尚未启用");
  }

  public String sendCode(String phone, String code) {
    return send(phone, otpTemplate, Map.of("code", code));
  }

  /** The returned BizId confirms channel acceptance, never handset delivery. */
  public String send(String phone, String templateCode, Map<String, String> parameters) {
    requireEnabled();
    PhoneIdentityService.validPhone(phone);
    if (templateCode == null || templateCode.isBlank())
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "SMS_TEMPLATE_MISSING", "短信模板尚未配置");
    try {
      var request =
          new SendSmsRequest()
              .setPhoneNumbers(phone)
              .setSignName(signName)
              .setTemplateCode(templateCode)
              .setTemplateParam(JSON.toJSONString(parameters));
      var result =
          client.sendSmsWithOptions(
              request,
              new RuntimeOptions()
                  .setAutoretry(false)
                  .setConnectTimeout(3000)
                  .setReadTimeout(10000));
      if (result.getBody() == null || !"OK".equals(result.getBody().getCode()))
        throw new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_REJECTED", "短信渠道未受理，请稍后重试");
      if (result.getBody().getBizId() == null || result.getBody().getBizId().isBlank())
        throw new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_INVALID_RESPONSE", "短信渠道响应无效");
      return result.getBody().getBizId();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      // Provider exceptions can embed the request, including the verification code.
      throw new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_UNAVAILABLE", "短信发送结果未确认，请稍后核对并重试");
    }
  }
}
