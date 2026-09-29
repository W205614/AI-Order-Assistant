package com.ai.assistant.controller;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.dto.ChatRequestDTO;
import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.service.OrderSafetyService;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.model.Dish;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.vo.ChatResponseVO;
import com.ai.assistant.vo.Result;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天接口（需用户 JWT）。
 * 校验用户后，把 userId + 用户 JWT + 消息转发给 Python Agent，
 * Agent 再用该 JWT 回调 Java 的订单/菜品接口（按用户隔离）。
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    private final AgentHttpClient agentHttpClient;
    private final AiProperties aiProperties;
    private final OrderSafetyService safetyService;
    private final OrderService orderService;

    public ChatController(AgentHttpClient agentHttpClient, AiProperties aiProperties,
                          OrderSafetyService safetyService, OrderService orderService) {
        this.agentHttpClient = agentHttpClient;
        this.aiProperties = aiProperties;
        this.safetyService = safetyService;
        this.orderService = orderService;
    }

    @PostMapping
    public Result<ChatResponseVO> chat(@Valid @RequestBody ChatRequestDTO dto,
                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Long userId = UserContext.getCurrentId();
        String jwtToken = UserContext.getToken();
        String traceId = validRequestId(requestId);
        OrderSafetyService.SafetyContext safety = safetyService.recordFromMessage(userId, dto.getMessage());
        if (safety.needsClarification()) {
            ChatResponseVO clarification = new ChatResponseVO();
            clarification.setTraceId(traceId);
            clarification.setReply("我识别到本次点餐可能有过敏约束。请在页面的“本次过敏原”中明确选择花生、鸡蛋或麸质；其他过敏原目前无法核验，请勿继续点餐。");
            clarification.setOutcome("needs_input");
            return Result.success(clarification);
        }
        if (orderService.hasActiveAllergens(userId, safety)
                && dto.getMessage().matches("(?is).*(推荐|吃什么|适合|recommend|能吃什么|可以吃什么).*$")) {
            // An allergy recommendation uses only Java's filtered, reviewed menu. No model-supplied
            // name or ID can enter the displayed candidate list.
            @SuppressWarnings("unchecked")
            List<Dish> candidates = (List<Dish>) orderService.listDishesForUser(
                    userId, null, null, true, 1, 50).get("items");
            ChatResponseVO safe = new ChatResponseVO();
            safe.setTraceId(traceId);
            safe.setOutcome("completed");
            if (candidates.isEmpty()) {
                safe.setReply("当前没有过敏原信息已核验且符合本次约束的可售菜品，请联系店员核实。");
            } else {
                String names = candidates.stream().limit(3)
                        .map(dish -> dish.getName() + "（" + dish.getPrice() + "元）")
                        .reduce((left, right) -> left + "、" + right).orElse("");
                safe.setReply("根据已核验的过敏原信息，本次可考虑：" + names + "。请在菜单中核对并选择，实际制作前仍需向店员确认交叉接触风险。");
            }
            return Result.success(safe);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("jwtToken", jwtToken);
        payload.put("requestId", traceId);
        payload.put("message", dto.getMessage());
        payload.put("history", dto.getHistory() == null ? List.of() : dto.getHistory());
        payload.put("selectedItems", dto.getSelectedItems() == null ? List.of() : dto.getSelectedItems());

        String url = aiProperties.getAgentBaseUrl() + aiProperties.getChatPath();
        try {
            String resp = agentHttpClient.doPostJson(url, payload, aiProperties.getTimeoutMs(), aiProperties.getInternalApiKey(), userId, traceId);
            JSONObject json = JSON.parseObject(resp);
            ChatResponseVO vo = new ChatResponseVO();
            vo.setTraceId(json.getString("traceId") == null ? traceId : json.getString("traceId"));
            vo.setReply(json.getString("reply"));
            vo.setOutcome(json.getString("outcome"));
            if (json.getJSONArray("citations") != null) {
                vo.setCitations(json.getJSONArray("citations").toJavaList(ChatResponseVO.Citation.class));
            }
            if (json.getJSONArray("toolCalls") != null) {
                vo.setToolCalls(json.getJSONArray("toolCalls").toJavaList(ChatResponseVO.ToolCallInfo.class));
            }
            if (json.getJSONArray("executionEvents") != null) {
                vo.setExecutionEvents(json.getJSONArray("executionEvents").toJavaList(ChatResponseVO.ExecutionEvent.class));
            }
            if (json.getJSONObject("pendingConfirmation") != null) {
                vo.setPendingConfirmation(json.getJSONObject("pendingConfirmation"));
            }
            return Result.success(vo);
        } catch (Exception e) {
            log.error("Failed to call Agent service traceId={}", traceId, e);
            return Result.error("AI 服务暂时不可用，请稍后再试");
        }
    }

    private String validRequestId(String requestId) {
        if (requestId != null && requestId.matches("[A-Za-z0-9._:-]{8,100}")) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }
}
