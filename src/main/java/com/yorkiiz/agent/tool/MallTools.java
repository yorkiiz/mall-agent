package com.yorkiiz.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yorkiiz.agent.client.MallApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * 导购 Agent 的 Function Calling 工具集。
 * 每次聊天请求 new 一个实例（携带当前用户的 JWT），请求结束即丢弃，无共享状态。
 */
public class MallTools {

    private static final Logger log = LoggerFactory.getLogger(MallTools.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final MallApiClient mall;
    private final String authorization; // 当前用户的 Authorization 头（可为 null）

    // 本轮对话 searchProducts 实际使用的筛选参数（前端"查看商品"按钮的数据来源）
    private String lastKeyword;
    private Double lastMinPrice;
    private Double lastMaxPrice;
    private int lastMatched = -1; // -1 表示本轮未调用过商品搜索

    public MallTools(MallApiClient mall, String authorization) {
        this.mall = mall;
        this.authorization = authorization;
    }

    /** 本轮调用过商品搜索则返回筛选参数+命中数，否则返回 null */
    public Map<String, Object> getLastFilters() {
        if (lastMatched < 0) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("keyword", lastKeyword);
        map.put("minPrice", lastMinPrice);
        map.put("maxPrice", lastMaxPrice);
        map.put("matched", lastMatched);
        return map;
    }

    @Tool(description = "按关键词和价格区间搜索商城在售商品。keyword 为商品名/描述关键词，可为空（空则返回最新商品）；minPrice/maxPrice 为价格下限/上限，用户没提价格就不要传。返回最多 5 个商品的 id、名称、价格、库存。")
    public String searchProducts(
            @ToolParam(required = false, description = "搜索关键词，如 '手机'、'鞋'") String keyword,
            @ToolParam(required = false, description = "价格下限（元），无要求时不传") Double minPrice,
            @ToolParam(required = false, description = "价格上限（元），无要求时不传") Double maxPrice) {
        log.info("[Tool] searchProducts keyword={} min={} max={}", keyword, minPrice, maxPrice);
        String result = mall.searchProducts(keyword, minPrice, maxPrice);
        // 记录实际参数，供 ChatController 返回给前端渲染"查看商品"按钮。
        // 模型一轮里可能多次调用本工具（如先筛选再无条件下单铺垫）：
        // 带筛选条件的调用才覆盖记录；无条件的搜索只在从未记录过时兜底，
        // 避免按钮从"41 条筛选结果"被后续无脑全量搜索覆盖成 320。
        boolean hasFilter = (keyword != null && !keyword.isBlank())
                || (minPrice != null && minPrice > 0)
                || (maxPrice != null && maxPrice > 0);
        if (hasFilter || lastMatched < 0) {
            this.lastKeyword = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
            this.lastMinPrice = minPrice;
            this.lastMaxPrice = maxPrice;
            this.lastMatched = 0;
            try {
                JsonNode node = OM.readTree(result);
                this.lastMatched = node.path("total").asInt(0);
            } catch (Exception ignore) {
            }
        }
        return result;
    }

    @Tool(description = "查询单个商品的详细信息（完整描述、精确价格、库存），用户对某个商品感兴趣时调用。id 来自 searchProducts 返回的商品 id。")
    public String getProductDetail(@ToolParam(description = "商品 id") long id) {
        log.info("[Tool] getProductDetail id={}", id);
        return mall.getProductDetail(id);
    }

    @Tool(description = "查询当前用户的订单列表。用户问'我的订单'、'我买过什么'时调用。如果用户未登录或 token 过期会返回失败原因。")
    public String getMyOrders() {
        log.info("[Tool] getMyOrders hasToken={}", authorization != null && !authorization.isBlank());
        return mall.getMyOrders(authorization);
    }
}
