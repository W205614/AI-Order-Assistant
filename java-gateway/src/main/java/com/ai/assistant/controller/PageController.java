package com.ai.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路径跳转。
 * Spring 默认不把目录路径（如 /chat/）自动落到 index.html，
 * 这里统一把 /chat、/chat/、/admin、/admin/ 都跳转到明确的 index.html。
 */
@Controller
public class PageController {

    @GetMapping({"/chat", "/chat/"})
    public String chatPage() {
        return "redirect:/chat/index.html";
    }

    @GetMapping({"/admin", "/admin/"})
    public String adminPage() {
        return "redirect:/admin/index.html";
    }
}
