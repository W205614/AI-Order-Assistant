package com.ai.assistant.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 点餐助手配置
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    /** Python Agent 服务地址 */
    private String agentBaseUrl = "http://localhost:8800";

    /** Python 聊天端点路径 */
    private String chatPath = "/chat";

    /** 调用 Python 超时时间(毫秒)，LLM 推理预留 */
    private long timeoutMs = 60000;

    /** 网关调用 Agent 的内部共享密钥；生产环境必须由环境变量注入。 */
    private String internalApiKey = "";
}
