# Agent 服务接口契约（Spring Boot ⇄ Python LangGraph）

> B4（internal 工具端点实现方）与 B5（Python 消费方 + Java 桥接）共同遵守。
> 所有字段 camelCase。

## A. Spring Boot → Python（Python 服务监听 127.0.0.1:8001）

### GET /health
`{"status":"ok","graph":"rental-search-v2"}`

### POST /agent/rental-search
请求（Java 桥接组装）：
```json
{
  "userId": 1,
  "query": "静安寺附近 6000 以内一室，安静，通勤30分钟",
  "city": "上海",
  "source": "text",                    // text | map_click
  "center": {"longitude": 121.44, "latitude": 31.22, "label": "静安寺"},  // 可空
  "radiusMeters": 2000,
  "settings": {"modelProfile": "deep", "defaultSort": "score_desc", "listingPageSize": 10}
}
```
响应：结构对齐旧 run_rental_search_agent_payload（读旧 rental_search_agent.py 确认），核心字段：
```json
{
  "ok": true,
  "intent": "rent_search_nearby",
  "queryText": "...",
  "parsed": {"city":"上海","locationText":"静安寺","radiusMeters":2000,"sort":"score_desc","constraints":{}},
  "center": {"longitude":0,"latitude":0,"label":"","city":"上海","coordinateSystem":"GCJ02","source":"amap_geocode"},
  "radiusMeters": 2000,
  "recommendations": [ {"...Listing 字段 camelCase...", "score":0, "reasons":[], "riskNotes":[], "distanceM":0, "withinRadius":true, "match":""} ],
  "markers": [ {"id":"","title":"","longitude":0,"latitude":0,"rentPrice":0} ],
  "toolTrace": [ {"tool":"parse_intent","status":"ok","summary":"..."} ],
  "summary": "自然语言总结",
  "warnings": [],
  "empty": false,
  "agent": {"name":"rental-search-graph","version":"2.0","mode":"llm|rules","userId":1,
             "intent":{"city":"","locationKeyword":"","budgetMax":null,"layout":null,
                        "preferences":[],"confidence":0.8,"missingFields":[],"rawText":""}}
}
```

### POST /agent/property-insight
请求：`{"userId":1,"listingId":"sh-001","listing":{...ListingEntity camelCase...},"focus":"balanced"}`
响应：对齐旧 run_property_insight_agent_payload（detailPatch/valueIndex/environmentEvaluation/commuteEvaluation/insight/pros/cons/analysisMeta/toolTrace/agent 块，读旧 property_insight_agent.py）。

### POST /agent/property-chat
请求：
```json
{"userId":1,"listingId":"sh-001","listing":{...},
 "history":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}],
 "message":"这个小区晚上吵吗？","modelProfile":"deep"}
```
响应：`{"ok":true,"content":"回答文本","citations":[{"label":"","value":""}],"toolTrace":[...]}`

失败语义：Python 服务任何失败返回 `{"ok":false,"code":"agent_error","summary":"..."}` + HTTP 200，或直接 5xx；Java 桥接两种都要处理并降级（rental-search 降级走 map-intent 规则链路，mode 标记 "rules_fallback"；insight/chat 降级走 DeepSeekClient 直连或规则文案）。

## B. Python → Spring Boot（回调 http://127.0.0.1:8080，请求头 X-Internal-Token: <renti.security.internal-token>）

由 B4 在 modules/search/api/InternalAgentToolsController 实现（/internal/** 已有令牌拦截器）。

### POST /internal/agent-tools/parse-requirement
`{"text":"...","city":"上海"}` → `{"city":"上海","budgetMax":6000,"layout":"1室","rentType":"整租","commuteLimitMinutes":30,"preferences":["安静"],"avoidances":[],"missingFields":[]}`（规则解析，对齐旧 requirements.py）

### POST /internal/agent-tools/geocode
`{"locationText":"静安寺","city":"上海"}` → `{"ok":true,"longitude":121.44,"latitude":31.22,"label":"静安寺","district":"静安区","source":"amap_geocode"}`；失败 `{"ok":false,"code":"geocode_failed"}`

### POST /internal/agent-tools/search-listings-sql
`{"city":"上海","centerLongitude":121.44,"centerLatitude":31.22,"radiusMeters":2000,"budgetMax":6000,"layout":"1室","rentType":null,"limit":40}`
→ `{"listings":[{...Listing camelCase...,"distanceM":850}], "total": 12}`（距离 Haversine，按距离升序；无 center 时按城市+条件）

### POST /internal/agent-tools/search-listings-vector
`{"text":"安静 一室 地铁近","city":"上海","limit":20}` → `{"hits":[{"listingId":"sh-001","score":0.83,"payload":{...}}]}`（B3 的 VectorSearchService）

### POST /internal/agent-tools/search-listings-graph
`{"listingIds":["sh-001"],"city":"上海"}` → `{"related":[{"listingId":"sh-001","relations":[{"type":"SAME_COMMUNITY","targetId":"sh-009"}]}]}`（B3 的 GraphQueryService.related；Neo4j 不可用返回空列表+warning）

### POST /internal/agent-tools/listing-detail
`{"listingId":"sh-001"}` → `{"ok":true,"listing":{...}}`

## C. Java 桥接（B5 的 modules/agent）

- AgentServiceClient（infrastructure/client）：调 Python（baseUrl=RentiProperties.agent().baseUrl()，超时 agent().timeoutSeconds()）
- AgentController：/api/agent/rental-search、/api/agent/property-insight、property-chat 三组端点（@CurrentUser）
- property-chat 会话持久化（PropertyChatSessionEntity/PropertyChatMessageEntity，B5 拥有）：会话列表/创建/发消息/清空 端点行为对齐旧 property_insight_agent.py 中 property_chat_* 函数
- 每次 rental-search/insight 调用后：AgentTraceService.record(...)（B3 提供）
- record_search_history/retrieval_audit/user_interaction 横切记录（B1/B3 提供的服务）
