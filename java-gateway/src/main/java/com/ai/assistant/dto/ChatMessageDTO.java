package com.ai.assistant.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 单条聊天消息（用于携带历史上下文）
 */
@Data
public class ChatMessageDTO {

    /** Browser-supplied history is context, never a privileged system message. */
    @NotBlank
    @Pattern(regexp = "user|assistant", message = "历史消息角色只能是 user 或 assistant")
    private String role;

    /** 消息内容 */
    @NotBlank
    @Size(max = 2000)
    private String content;
}
