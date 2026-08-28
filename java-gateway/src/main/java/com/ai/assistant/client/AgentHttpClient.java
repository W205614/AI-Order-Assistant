package com.ai.assistant.client;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 调用 Python LangGraph Agent 服务的 HTTP 客户端。
 * 支持任意嵌套 JSON body（history 数组）与可配置长超时（LLM 推理慢）。
 */
@Component
@Slf4j
public class AgentHttpClient {

    /** 连接池随 Spring 单例复用；不能按每个聊天请求重复建连。 */
    private final CloseableHttpClient httpClient = HttpClients.custom()
            .evictExpiredConnections()
            .build();

    /**
     * 向 Python Agent 发送 JSON POST 请求
     *
     * @param url       完整 URL
     * @param body      任意 Java 对象（会被 fastjson2 序列化为 JSON）
     * @param timeoutMs 超时(毫秒)
     * @return 响应体字符串
     */
    public String doPostJson(String url, Object body, long timeoutMs, String internalApiKey, Long userId) throws IOException {
        HttpPost httpPost = new HttpPost(url);
        RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                    .setResponseTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                    .build();
        httpPost.setConfig(config);
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            httpPost.setHeader("X-Agent-Internal-Key", internalApiKey);
        }
        if (userId != null) {
            httpPost.setHeader("X-Agent-User-Id", String.valueOf(userId));
        }

        String json = JSON.toJSONString(body);
        StringEntity entity = new StringEntity(json, ContentType.APPLICATION_JSON);
        httpPost.setEntity(entity);

        // Log URL and size only (full Chinese body would garble in GBK console and spam logs)
        log.info("Forward to Agent: POST {} (body {} chars)", url, json.length());
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = response.getEntity() == null
                    ? "" : EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                log.warn("Agent request failed: HTTP {}", status);
                throw new IOException("Agent 服务返回 HTTP " + status);
            }
            if (responseBody.isBlank()) {
                throw new IOException("Agent 服务返回空响应");
            }
            return responseBody;
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("解析 Agent 响应失败", e);
        }
    }

    @PreDestroy
    void close() throws IOException {
        httpClient.close();
    }
}
