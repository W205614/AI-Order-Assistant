package com.ai.assistant.controller;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import com.alibaba.fastjson2.JSON;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/campus-assistant")
public class CampusAssistantController {
  private final CampusRecommendationService recommendations;
  private final CampusCatalogService catalog;
  private final AgentHttpClient agent;
  private final AiProperties properties;
  private final OrderSafetyService safety;
  private final CampusChatAdmission admission;

  public CampusAssistantController(
      CampusRecommendationService recommendations,
      CampusCatalogService catalog,
      AgentHttpClient agent,
      AiProperties properties,
      OrderSafetyService safety,
      CampusChatAdmission admission) {
    this.recommendations = recommendations;
    this.catalog = catalog;
    this.agent = agent;
    this.properties = properties;
    this.safety = safety;
    this.admission = admission;
  }

  @GetMapping("/search")
  public Result<?> search(
      @RequestParam long campusId, @RequestParam(defaultValue = "") String keyword) {
    return Result.success(recommendations.search(campusId, keyword));
  }

  @PostMapping("/recommend")
  public Result<?> recommend(@RequestBody CampusRecommendationService.Request x) {
    return Result.success(recommendations.recommend(x));
  }

  @PostMapping("/prepare-cart")
  public Result<?> prepare(@RequestBody CampusRecommendationService.PrepareRequest x) {
    return Result.success(recommendations.prepareCart(x));
  }

  public record Chat(long campusId, Long addressId, String fulfillmentMode, String message) {}

  @PostMapping("/chat")
  public Result<?> chat(@RequestBody Chat x) {
    UserContext.requireRole("CUSTOMER");
    if (x.message() == null || x.message().isBlank() || x.message().length() > 2000)
      throw new IllegalArgumentException("消息需1-2000字");
    long deadline = UserContext.effectiveDeadline(
        System.currentTimeMillis() + Math.min(35000, properties.getTimeoutMs()));
    return UserContext.withinDeadline(deadline, () -> {
      try (var lease = admission.acquire(UserContext.getCurrentId())) {
        UserContext.checkDeadline();
        return chatAdmitted(x, deadline);
      }
    });
  }

  private Result<?> chatAdmitted(Chat x, long deadline) {
    var merchants = catalog.shops(x.campusId()).stream()
        .map(s -> ((Number) s.get("id")).longValue()).toList();
    safety.recordAcrossMerchants(UserContext.getCurrentId(), x.message(), merchants);
    var payload = new HashMap<String, Object>();
    payload.put("userId", UserContext.getCurrentId());
    payload.put("merchantId", 0);
    payload.put("campusId", x.campusId());
    payload.put("addressId", x.addressId());
    payload.put("fulfillmentMode", x.fulfillmentMode());
    payload.put("message", x.message());
    payload.put("jwtToken", UserContext.getToken());
    payload.put("requestId", UUID.randomUUID().toString());
    payload.put("deadlineEpochMs", deadline);
    try {
      return Result.success(
          JSON.parseObject(
              agent.doPostJson(
                  properties.getAgentBaseUrl() + "/campus-chat",
                  payload,
                  Math.min(properties.getTimeoutMs(), UserContext.remainingMillis(deadline)),
                  properties.getInternalApiKey(),
                  UserContext.getCurrentId(),
                  (String) payload.get("requestId"))));
    } catch (BusinessException e) {
      throw e;
    } catch (org.apache.hc.client5.http.ConnectTimeoutException e) {
      // ConnectTimeoutException also extends SocketTimeoutException; no Agent connection exists yet.
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 暂不可用，请使用菜单继续点餐");
    } catch (java.net.SocketTimeoutException e) {
      throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "AI 请求超时，请使用菜单继续点餐");
    } catch (java.io.IOException e) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 暂不可用，请使用菜单继续点餐");
    }
  }
}
