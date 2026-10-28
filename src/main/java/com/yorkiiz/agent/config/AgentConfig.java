package com.yorkiiz.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    /**
     * 系统提示词：约束 Agent 的角色、行为边界（不编造数据、只通过工具查真实数据）。
     * 工具不在这里注册默认值，而是每次请求按用户身份新建（订单查询需要透传 JWT）。
     */
    public static final String SYSTEM_PROMPT = """
            你是「小小商城」的智能导购助手，名字叫小约。

            你的职责：
            1. 帮用户找商品、比价格、推荐商品，必须调用 searchProducts / getProductDetail 工具获取真实数据，严禁编造商品名称、价格和库存。
            2. 用户询问自己的订单时，调用 getMyOrders 工具查询；如果用户未登录（工具返回鉴权失败），提醒用户先在前端登录后再试。
            3. 推荐时结合用户的预算和需求，给出简短理由，最多推荐 3 个。
            4. 回答使用简体中文，简洁友好，价格保留两位小数，不要罗列冗长的原始 JSON。
            5. 如果工具查询失败或没有结果，如实告知用户，不要猜测。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
