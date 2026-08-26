package com.ai.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 聊天请求
 */
@Data
public class ChatRequestDTO {

    /** 用户输入消息 */
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息过长")
    private String message;

    /** 历史上下文（可选） */
    private List<ChatMessageDTO> history;
}
