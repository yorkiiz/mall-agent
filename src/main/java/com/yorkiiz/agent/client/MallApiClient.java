package com.yorkiiz.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 商城后端 API 客户端。
 * 本地直连 localhost:8080；集群内通过 K8s Service DNS（backend-svc）访问。
 */
@Component
public class MallApiClient {

    private static final Logger log = LoggerFactory.getLogger(MallApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MallApiClient(org.springframework.core.env.Environment env) {
        String baseUrl = env.getProperty("mall.api.base-url", "http://localhost:8080");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        log.info("MallApiClient 初始化完成，商城后端地址: {}", baseUrl);
    }

    /** 搜索商品：keyword/价格区间均可选，返回精简后的商品列表 JSON 字符串 */
    public String searchProducts(String keyword, Double minPrice, Double maxPrice) {
        JsonNode data = get("/api/product/list", b -> {
            b.queryParam("pageNum", "1");
            b.queryParam("pageSize", "5");
            if (keyword != null && !keyword.isBlank()) {
                b.queryParam("keyword", keyword.trim());
            }
            if (minPrice != null && minPrice > 0) {
                b.queryParam("minPrice", String.valueOf(minPrice));
            }
            if (maxPrice != null && maxPrice > 0) {
                b.queryParam("maxPrice", String.valueOf(maxPrice));
            }
        });
        if (data == null) {
            return "查询商品失败：商城接口无响应";
        }
        // 只保留 LLM 需要的字段，压缩上下文长度；total 供前端"查看商品"按钮使用
        ObjectNode out = objectMapper.createObjectNode();
        JsonNode records = data.path("records");
        ArrayNode items = objectMapper.createArrayNode();
        for (JsonNode p : records) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", p.path("id").asLong());
            item.put("name", p.path("name").asText());
            item.put("price", p.path("price").asDouble());
            item.put("stock", p.path("stock").asInt());
            String desc = p.path("description").asText("");
            if (desc.length() > 60) {
                desc = desc.substring(0, 60) + "...";
            }
            item.put("description", desc);
            items.add(item);
        }
        out.put("total", data.path("total").asInt());
        out.set("items", items);
        return out.toString();
    }

    /** 商品详情 */
    public String getProductDetail(long id) {
        JsonNode data = get("/api/product/detail/" + id, b -> {});
        if (data == null || data.isMissingNode() || data.isNull()) {
            return "商品不存在（id=" + id + "）";
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", data.path("id").asLong());
        item.put("name", data.path("name").asText());
        item.put("price", data.path("price").asDouble());
        item.put("stock", data.path("stock").asInt());
        item.put("description", data.path("description").asText(""));
        return item.toString();
    }

    /** 查询当前用户订单（需要透传用户 JWT） */
    public String getMyOrders(String authorization) {
        try {
            String body = restClient.get()
                    .uri("/api/order/list")
                    .header(HttpHeaders.AUTHORIZATION, authorization == null ? "" : authorization)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt() != 200) {
                return "订单查询失败：" + root.path("message").asText("未知错误") + "（用户可能未登录或 token 已过期）";
            }
            return root.path("data").toString();
        } catch (Exception e) {
            log.warn("订单查询失败: {}", e.getMessage());
            return "订单查询失败：" + e.getMessage();
        }
    }

    /** 统一 GET + 解析 Result{code,message,data}，code!=200 返回 message */
    private JsonNode get(String uri, java.util.function.Consumer<org.springframework.web.util.UriBuilder> params) {
        try {
            String body = restClient.get()
                    .uri(uri, params)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt() != 200) {
                return objectMapper.nullNode();
            }
            return root.path("data");
        } catch (Exception e) {
            log.warn("调用商城接口失败 uri={}: {}", uri, e.getMessage());
            return null;
        }
    }
}
