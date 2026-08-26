package com.ai.assistant.vo;

import lombok.Data;

import java.util.List;

/**
 * 聊天响应
 */
@Data
public class ChatResponseVO {

    /** 助手回复文本 */
    private String reply;

    /** 引用来源（FAQ 命中时可选） */
    private List<Citation> citations;

    /** 本次对话调用的工具记录（可选） */
    private List<ToolCallInfo> toolCalls;

    @Data
    public static class Citation {
        private String title;
        private String content;
    }

    @Data
    public static class ToolCallInfo {
        private String tool;
        private String status;
    }
}
