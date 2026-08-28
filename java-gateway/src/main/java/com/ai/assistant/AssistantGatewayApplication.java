package com.ai.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import java.util.Map;

/**
 * AI 点餐助手 - Java 后端（点餐/订单/菜品业务逻辑 + 聊天转发）
 */
@SpringBootApplication
@EnableCaching
public class AssistantGatewayApplication {

    public static void main(String[] args) {
        // 本地未配置 Redis 时不自动探测并连接；Compose 通过 CACHE_TYPE=redis 显式覆盖。
        SpringApplication application = new SpringApplication(AssistantGatewayApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.cache.type", System.getenv().getOrDefault("CACHE_TYPE", "simple")));
        application.run(args);
    }
}
