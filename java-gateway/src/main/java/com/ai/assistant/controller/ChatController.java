package com.ai.assistant.controller;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.dto.ChatRequestDTO;
import com.ai.assistant.properties.AiProperties;
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

    public ChatController(AgentHttpClient agentHttpClient, AiProperties aiProperties) {
        this.agentHttpClient = agentHttpClient;
        this.aiProperties = aiProperties;
    }

    @PostMapping
    public Result<ChatResponseVO> chat(@Valid @RequestBody ChatRequestDTO dto,
                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Long userId = UserContext.getCurrentId();
        String jwtToken = UserContext.getToken();

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("jwtToken", jwtToken);
        payload.put("requestId", validRequestId(requestId));
        payload.put("message", dto.getMessage());
        payload.put("history", dto.getHistory() == null ? List.of() : dto.getHistory());
        payload.put("selectedItems", dto.getSelectedItems() == null ? List.of() : dto.getSelectedItems());

        String url = aiProperties.getAgentBaseUrl() + aiProperties.getChatPath();
        try {
            String resp = agentHttpClient.doPostJson(url, payload, aiProperties.getTimeoutMs(), aiProperties.getInternalApiKey(), userId);
            JSONObject json = JSON.parseObject(resp);
            ChatResponseVO vo = new ChatResponseVO();
            vo.setReply(json.getString("reply"));
            if (json.getJSONArray("citations") != null) {
                vo.setCitations(json.getJSONArray("citations").toJavaList(ChatResponseVO.Citation.class));
            }
            if (json.getJSONArray("toolCalls") != null) {
                vo.setToolCalls(json.getJSONArray("toolCalls").toJavaList(ChatResponseVO.ToolCallInfo.class));
            }
            if (json.getJSONObject("pendingConfirmation") != null) {
                vo.setPendingConfirmation(json.getJSONObject("pendingConfirmation"));
            }
            return Result.success(vo);
        } catch (Exception e) {
            log.error("Failed to call Agent service", e);
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
