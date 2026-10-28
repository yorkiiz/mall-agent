# mall-agent — 智能导购 Agent（Spring AI Function Calling）

架构：**用户请求 → mall-agent（ChatClient + Function Calling）→ 工具调用商城后端真实接口 → LLM 组织答案**

```
浏览器/前端
   │  POST /api/agent/chat（Authorization 透传）
   ▼
mall-agent (Spring Boot 3.5 + Spring AI 1.0, JDK17)
   │  ① ChatClient(qwen2:7b / deepseek-chat) 决策调用哪个工具
   │  ② Function Calling 执行 MallTools：
   │       searchProducts  → GET /api/product/list   （公开）
   │       getProductDetail→ GET /api/product/detail （公开）
   │       getMyOrders     → GET /api/order/list     （透传用户 JWT）
   │  ③ 工具结果回填给 LLM 生成自然语言回答
   ▼
backend-svc:8080（mall_demo 商城后端）
```

关键设计（面试可讲）：
- **开发/生产模型解耦**：统一走 OpenAI 兼容协议，local profile 指向本地 Ollama（`http://localhost:11434/v1`），prod profile 指向 DeepSeek API，密钥走 K8s Secret 注入，代码零改动切换。
- **JWT 鉴权透传**：订单接口需要登录态。Agent 不做账号体系，而是把用户请求头里的 `Authorization` 原样传给工具 → 后端。每次聊天 new 一个 `MallTools` 实例携带 token，无共享状态。
- **工具结果裁剪**：商品列表只保留 id/name/price/stock/描述前 60 字，压缩 LLM 上下文长度，降低 token 成本。
- **节点亲和**：Deployment 用 nodeSelector 固定到 slave2 —— slave1 无公网 IP，调不通 AI API。

## 一、本地运行（Ollama）

前置：本地 Ollama 已有 `qwen2:7b`（支持 function calling；若工具调用不稳，`ollama pull qwen2.5:7b` 并把 `application-local.yml` 的 model 改掉）；本地商城后端 8080 已启动。

```bash
cd E:/code/spring/start/mall-agent
mvn spring-boot:run
```

验证（另开终端）：

```bash
# 1. 搜索商品
curl -s -X POST http://localhost:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"帮我找50块以内的商品\"}"

# 2. 多轮
curl -s -X POST http://localhost:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"第一个的详情给我看看\",\"history\":[{\"role\":\"assistant\",\"content\":\"<上轮回答粘这里>\"}]}"

# 3. 订单（带登录 token）
curl -s -X POST http://localhost:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <你的JWT>" \
  -d "{\"message\":\"我买过什么东西\"}"
```

## 二、打包镜像（Jib，本机无需 Docker）

```bash
mvn package jib:buildTar -DskipTests
# 产物：target/jib-image.tar
```

## 三、导入集群并部署

```bash
# 1. 传到 master 并导入 containerd（-n k8s.io 必须带）
scp target/jib-image.tar root@47.98.20.61:/root/
ssh root@47.98.20.61 "ctr -n k8s.io images import /root/jib-image.tar && crictl inspecti docker.io/library/mall-agent:1.0.0 | head -5"

# 2. slave2 也要能调度到该镜像？不用 —— nodeSelector 已固定 slave2，
#    若 import 到 master 需先检查 pod 实际落在哪个节点，落在 slave2 则要分发：
ssh root@47.98.20.61 "ctr -n k8s.io images export /root/mall-agent.tar docker.io/library/mall-agent:1.0.0"
scp root@47.98.20.61:/root/mall-agent.tar /tmp/
scp /tmp/mall-agent.tar root@114.55.145.124:/root/
ssh root@114.55.145.124 "ctr -n k8s.io images import /root/mall-agent.tar"

# 3. 填密钥后 apply（编辑 k8s/agent.yaml 中 Secret 的 AI_API_KEY）
kubectl apply -f k8s/agent.yaml
kubectl get pods -l app=mall-agent -o wide

# 4. 集群内验证
kubectl run curl-test --rm -it --image=curlimages/curl --restart=Never -- \
  curl -s -X POST http://mall-agent-svc:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"推荐一个100元以内的商品"}'
```

## 四、（可选）前端 nginx 转发

见 `k8s/nginx-agent.conf`，把 location 块加进 `/root/myapp/conf/default.conf` 后重启前端 deployment。不加也行，直接 curl 集群内验证/录演示视频足够。

## 五、换模型供应商

prod 环境改 DeepSeek 之外任何 OpenAI 兼容 API（通义/智谱/硅基流动），在 `k8s/agent.yaml` 的 env 里追加：

```yaml
- name: AI_BASE_URL
  value: https://dashscope.aliyuncs.com/compatible-mode/v1
- name: AI_MODEL
  value: qwen-plus
```

## 常见坑

| 现象 | 原因 |
|---|---|
| pod CrashLoop：`AI_API_KEY` 为空 | Secret 里没填真实密钥 |
| Agent 报"商城接口无响应" | `MALL_API_BASE` 没指到 backend-svc，或 backend 副本为 0 |
| 工具从不被调用 | qwen2:7b 工具能力偏弱，换 qwen2.5:7b 或 deepseek-chat |
| pod 调度失败 0/1 | 镜像没导入到 slave2 的 containerd |

## 六、前端入口（已实现）

`front` 工程已集成：右下角悬浮球 → 抽屉式聊天窗（`src/components/AgentChat.vue`，挂在 App.vue 全局可见）。
- 请求走统一 axios 实例：`/api/agent/chat`，token 拦截器自动带 Authorization
- vite 代理：`/api/agent` → localhost:8081（本地），线上由 nginx 的 `/api/agent` location 转发
- 快捷提问标签：「推荐50元以内的商品」「有什么鞋类？」「我买过什么东西」（第三个验证 JWT 透传链路）
