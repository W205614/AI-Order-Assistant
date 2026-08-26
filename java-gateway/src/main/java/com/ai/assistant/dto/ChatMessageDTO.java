package com.ai.assistant.dto;

import lombok.Data;

/**
 * 单条聊天消息（用于携带历史上下文）
 */
@Data
public class ChatMessageDTO {

    /** 角色：user / assistant / system */
    private String role;

    /** 消息内容 */
    private String content;
}
