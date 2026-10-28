package com.yorkiiz.agent.controller;

import com.yorkiiz.agent.client.MallApiClient;
import com.yorkiiz.agent.config.AgentConfig;
import com.yorkiiz.agent.tool.MallTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class ChatController {

    private final ChatClient chatClient;
    private final MallApiClient mallApiClient;

    public ChatController(ChatClient chatClient, MallApiClient mallApiClient) {
        this.chatClient = chatClient;
        this.mallApiClient = mallApiClient;
    }

    public static class ChatRequest {
        private String message;
        /** 可选多轮上下文：[{role:"user"|"assistant", content:"..."}]，按时间正序 */
        private List<Map<String, String>> history;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<Map<String, String>> getHistory() { return history; }
        public void setHistory(List<Map<String, String>> history) { this.history = history; }
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (!StringUtils.hasText(req.getMessage())) {
            return Map.of("code", 400, "message", "message 不能为空");
        }

        // 每个请求独立工具实例：携带该用户的 JWT，用于订单接口鉴权透传
        MallTools tools = new MallTools(mallApiClient, authorization);

        List<Message> messages = new ArrayList<>();
        if (req.getHistory() != null) {
            for (Map<String, String> h : req.getHistory()) {
                String role = h.getOrDefault("role", "user");
                String content = h.getOrDefault("content", "");
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                messages.add("assistant".equals(role)
                        ? new AssistantMessage(content)
                        : new UserMessage(content));
            }
        }

        long start = System.currentTimeMillis();
        String reply = chatClient.prompt()
                .messages(messages)
                .user(req.getMessage())
                .tools(tools)
                .call()
                .content();
        long cost = System.currentTimeMillis() - start;

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", 200);
        body.put("reply", reply == null ? "" : reply);
        body.put("costMs", cost);
        // 本轮模型实际执行过商品搜索时，把筛选参数透出给前端渲染"查看商品"按钮
        java.util.Map<String, Object> filters = tools.getLastFilters();
        if (filters != null) {
            body.put("filters", filters);
        }
        return body;
    }
}
