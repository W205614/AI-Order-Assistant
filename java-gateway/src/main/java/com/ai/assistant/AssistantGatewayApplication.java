package com.ai.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * AI 点餐助手 - Java 后端（点餐/订单/菜品业务逻辑 + 聊天转发）
 */
@SpringBootApplication
@EnableCaching
public class AssistantGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantGatewayApplication.class, args);
    }
}
