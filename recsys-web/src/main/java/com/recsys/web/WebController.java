package com.recsys.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 演示前端:输入 userId → 调 rec-engine 展示推荐结果。
 * 前端用 JS fetch 调用后端,这里只渲染页面 + 注入后端地址。
 */
@Controller
public class WebController {

    @Value("${recsys.web.rec-engine-url:http://localhost:8081}")
    private String recEngineUrl;

    @Value("${recsys.web.behavior-url:http://localhost:8082}")
    private String behaviorUrl;

    @GetMapping("/")
    public String index(@RequestParam(required = false, defaultValue = "1") long userId, Model model) {
        model.addAttribute("userId", userId);
        model.addAttribute("recEngineUrl", recEngineUrl);
        model.addAttribute("behaviorUrl", behaviorUrl);
        return "index";
    }
}
