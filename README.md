# ai-workbench

一套 AI 应用底座，正演进为一个**企业级 AI 管理平台**（Java 全栈 / Spring Boot 3 + LangChain4j + Vue3）。

> 愿景：一个企业管理控制台 + 一个可执行任务的智能体
> —— 智能体通过工具中心调用后台接口执行任务（查询、交易、知识检索），可操作数据库，
> 并受企业级约束：登录鉴权、工具级权限、审计落库、不可逆操作人工审批（HITL）

---

## 架构

```
前端（Vue3 企业管理控制台，Phase 2 改造布局）
        │  统一 /api 入口
platform-core（智能体运行时 + 平台横切能力）
  ├─ Agent 运行时：AgentSpec 注册 / AiServices 装配 / SSE 流式 / 会话记忆持久化
  ├─ 工具中心：各业务模块注册 @Tool，Agent 调用后执行 → Service → 事务 → DB
  └─ 横切（Phase 2 补齐）：登录鉴权 → 工具级权限 → 审计 → 审批(HITL)
业务模块：app-bank（交易）/ app-knowledge（RAG）/ app-interview（面试）
```

**核心设计原则「读宽写窄」**：AI 可以获得受限的只读查询能力（白名单表 + 强制 LIMIT）做分析报表；
但写操作永远不给裸 SQL，必须走业务 Service（规则校验 + 事务）+ 人工确认。

---

## Phase 1 银行交易 Agent 完整演示

以下是「给李四转 200 元」的完整时序图和对话流程：

```
用户：给李四转 200 元
  ↓
【模型调用 transfer() 工具】
  - 只创建 PENDING 确认单，不碰钱
  - SSE 推送 confirm_request 事件
  ↓
【前端收到事件，渲染确认卡片】
  - 卡片显示：付款方（张三）→ 收款方（李四）→ 金额（200.00）→ 附言
  - 两个按钮：「确认转账」「取消」
  ↓
用户：确认
  ↓
【模型调用 confirmOrder(confirmId, true)】
  - 状态机 CAS：UPDATE bank_transfer_order SET status='EXECUTED'
    WHERE confirm_id=? AND status='PENDING'
  - 规则复检（余额充足，无超限）
  - 乐观扣款：UPDATE bank_account SET balance=balance-? WHERE account_no=? AND balance>=?
  - 双向流水落库
  - 同事务提交
  ↓
【前端收到确认结果】
  - 按钮消失，显示「✅ 已确认」+ 回执消息
  - 张三余额：12850.50 → 12650.50
```

**核心设计点**：
- **执行权在人类手里**：模型只能创建确认单，资金变动的唯一入口是确认卡片
- **幂等核心**：同一 confirmId 重复提交只会执行一次（CAS 阻塞）
- **数据库是唯一事实来源**：模型看不见确认单真实状态，必须通过 `queryTransferOrder()` 工具查库

完整演示脚本见 `PHASE1_DEMO.md`（5 个验收场景 + 2 个附加场景）。

---

## 模块结构

| 模块 | 说明 | 端口 |
|---|---|---|
| platform-core | LLM 接入、SSE 流式、会话记忆、Agent 框架、RAG 配置位、可观测雏形 | - |
| app-bank | Phase 1 旗舰：带风控的银行交易 Agent（数据已落库 MySQL） | 8081 |
| app-knowledge | Phase 3：多源知识库路由 Agent（当前占位） | 8082 |
| app-interview | Phase 3 后：AI 面试模拟器（当前占位，已可对话） | 8083 |
| frontend | Vue3 + Vite 控制台，一个前端对接三个应用 | 5173 |

> Phase 2 将把三个应用合并为单一 `app-platform`（Maven 模块保留，代码组织不变）。

---

## 快速开始

前置：JDK 21、Maven 3.9+、Docker。

```bash
# 1. 在项目根目录创建 .env（已被 gitignore，模板见下），Spring 经 spring-dotenv 读取，
#    docker compose 也读同一份——一套变量两边生效
#    MYSQL_PASSWORD=204512
#    GLM_API_KEY=xxxxxxxx.xxxxxxxx

# 2. 启动中间件（MySQL 必需；Redis/Postgres 为后续阶段预留）
#    注意：容器 MySQL 映射在 13306（本机 3306 已被 Windows 服务占用）
docker compose up -d

# 3. 构建并运行旗舰应用
mvn -DskipTests package
mvn spring-boot:run -pl app-bank

# 4. 启动前端
cd frontend && npm install && npm run dev
#    打开 http://localhost:5173 → 目录页进入各 Agent
```

换模型只改环境变量（默认 GLM）：`AI_LLM_BASE_URL` / `AI_LLM_API_KEY` / `AI_LLM_MODEL`，
例如切 DeepSeek：`https://api.deepseek.com/v1` + `deepseek-chat`。默认指向智谱 OpenAI 兼容端点
`https://open.bigmodel.cn/api/paas/v4`（注意不是 anthropic 端点，底座用 OpenAI 协议接入）。

> 本机默认 JDK 是 8，构建/运行需切 JDK 21：`export JAVA_HOME=/d/download/code/jdkVersion/jdk21`

---

## 测试

```bash
# 全量测试（单元 + 集成），需 JDK 21
mvn test

# 只跑银行模块
mvn -pl app-bank -am test

# 前端类型检查 + 单元测试
cd frontend && npm run verify
```

**分层**：

| 层 | 位置 | 依赖 | 说明 |
|---|---|---|---|
| 后端单元测试 `*Test` | `platform-core`、`app-bank` 的 `src/test/...` | 无 | 护栏决策与滑动窗口限流、风控规则、身份解析、工具权限边界、工具上下文 |
| 后端集成测试 `*IT` | `app-bank/src/test/...` | 真实 MySQL | 转账两段式状态机：建单/确认/幂等/过期/并发/取消/审计 |
| 前端单元测试 `*.spec.ts` | `frontend/src/**/__tests__/` | jsdom | SSE 帧解析与分片拼装、身份与 memoryId 绑定、登录态、护栏 429 提示、日志查询参数拼装、图表生命周期 |

前端测试里的假 Response 是**手写**的（只实现被测代码用到的 `ok`/`status`/`body.getReader`/`json`），
不依赖 jsdom 是否提供 `ReadableStream`/`Response`——测试不该因为环境差异而假失败。

集成测试**刻意不用 H2**：被测逻辑的价值几乎全在 MySQL 语义里（`UPDATE ... WHERE status='PENDING'`
的 CAS 幂等、`balance >= ?` 乐观扣款、DECIMAL 精度、聚合口径），换内存库等于换了个被测对象。
测试直连独立库 `ai_workbench_test`（与开发库隔离，可随时清空），建表**跑的是各模块真实的
Flyway 迁移**（先 `clean` 再 `migrate`），所以迁移脚本一旦写错，`mvn test` 立刻失败，
而不是等到应用启动才炸。

**CI**：`.github/workflows/ci.yml` 两个 job——后端用 MySQL 8.4 service 容器跑 `mvn test`
（`TEST_MYSQL_REQUIRED=true`，数据库没起来就直接失败而不是静默跳过）；前端 `npm ci` 后跑
类型检查与单测。

**数据库迁移**：表结构由 Flyway 版本化迁移管理（`各模块/src/main/resources/db/migration/`），
启动时自动升级，执行记录落在 `flyway_schema_history` 表。迁移一旦提交就不再修改
（校验和会拒绝启动），后续变更一律新增 `V{n}__*.sql`。版本号跨模块**全局递增**
（`V1` = platform-core 的 chat_memory，`V2`/`V3` = app-bank），不能各模块从 V1 重新数。

> 引入 Flyway 之前是「每次启动执行 `schema.sql`」：`CREATE TABLE IF NOT EXISTS` 对已存在的表
> 不生效，列变更只能靠启动时查 `information_schema` 再 `ALTER` 的补丁类兜底，且没有任何地方
> 记录「这个库升到哪一版」——存量库会悄悄停在旧结构上，出现「测试全绿但真实库缺列」的假象。
> 存量库的收敛靠 `baselineOnMigrate` + `baselineVersion=0`（让所有迁移都重跑一遍）配合
> 幂等迁移语句与 `V3` 的存在性判断完成。

**环境变量**：

| 变量 | 默认 | 说明 |
|---|---|---|
| `TEST_MYSQL_URL` | `jdbc:mysql://localhost:13306/ai_workbench_test?createDatabaseIfNotExist=true...` | 测试库地址 |
| `TEST_MYSQL_USER` / `TEST_MYSQL_PASSWORD` | `root` / `MYSQL_PASSWORD` 或 `204512` | 测试库凭证 |
| `TEST_MYSQL_REQUIRED` | 未设置 | 设为 `true` 时，测试库不可用将**直接失败**而不是跳过（CI 必须设，否则数据库没起来会让集成测试静默消失、构建依然全绿） |

本机没有 MySQL 时，集成测试会自动跳过，`mvn test` 仍可全绿（本地开发友好）。

---

## 接口

- `GET /api/chat/{agent}/stream?memoryId=会话ID&message=输入` — SSE 流式对话
  （事件：`delta` 增量 / `done` 含 token 用量 / `error` 含 traceId）
- `GET /api/chat/agents` — 已注册的 Agent 列表
- `GET /api/platform/workbench` — 首页工作台数据（任何登录用户；全是聚合数字）
- `GET /api/platform/search?q=&limit=` — 全局搜索：应用入口 / 运行日志 / 已注册接口
- `GET /api/admin/platform-logs?app&category&result&keyword&page&size` — 平台运行日志（ADMIN）
- `GET /{agent}` 对应应用静态页（目前 app-bank 有聊天 demo 页）

---

## 工作台与平台运行日志

首页的每一格数字都对应一句真实 SQL，没有前端写死的常量。数据来源：

| 位置 | 来源 |
|---|---|
| 能力胶囊：模型数 / SSE P95 / 会话记忆 / 工具数 | `llm_usage`、`platform_event_log`、`chat_memory` |
| 银行助手卡片：今日会话 / 工具调用 / 成功率 | `platform_event_log`（app=bank） |
| 知识库卡片：文档数 / 检索 P95 | `knowledge_document`、`knowledge_query_log.latency_ms` |
| 面试卡片：累计场次 / 平均分 | `interview_session`（未结束的场次不计入平均分） |
| 页脚：今日事件 / 平均延迟 / 成功率 | `platform_event_log` |
| 页脚：已注册接口数 | Spring 已注册的 `RequestMappingHandlerMapping`（不是写死的数字——写死的数字加一个接口就过期） |
| 通知中心 | 待审批转账、今日风控拒绝、今日护栏拦截（**仅 ADMIN**） |

**平台运行日志**（`platform_event_log`，V6 迁移）与 `bank_audit_log` 是两回事，消费者不同：

- `bank_audit_log`：银行业务域的合规审计，字段与语义为银行定制，只记业务工具调用，
  给管理后台审计页与合规追溯；
- `platform_event_log`：平台运行日志，形状跨应用统一，除工具调用外还记对话、登录、
  护栏拒绝、知识库检索、面试轮次，给工作台首页与「查看日志」。

`ToolAuditLogger` 是工具调用的唯一收口，因此在这里各写一份。

`/logs` 页面按应用/类别/结果/关键字筛选，分页，汇总条**不随分页变化**（否则翻页时数字跳动，
读起来像 bug）。筛选条件同步到 URL，日志地址可以直接贴给别人还原现场。每行带 TraceId，
复制它即可在日志里拉出这次请求贯穿的全部行。

**关于演示数据**：`platform_event_log`、`knowledge_document`、`knowledge_query_log`、
`interview_session`、`llm_usage` 由 `PlatformDemoDataSeeder` 在表为空时播种一批**仿真业务数据**
（48 篇银行制度/产品/监管语料、110 条检索日志、14 场面试、约 350 条运行事件）。
`bank_account` / `bank_transaction` / `bank_audit_log` 三张业务与合规表**不造数**，只记真实操作。

> 为什么用 `ApplicationRunner` 而不是 Flyway 迁移：这些数据的价值全在时间分布上——首页要算
> 「今日会话」「近 7 天 P95」。迁移里写死绝对时间的话，跑起来第二天就全变成历史数据、页面立刻空掉；
> 播种按相对当前时间生成，每次全新部署都能看到一个「正在运行」的平台。表为空才播种，
> 所以重启不会让数字虚增。

**应用在线状态**：工作台接口跑在 app-bank 里，它的 `AgentRegistry` 只登记了银行助手，
另两个应用是独立进程。因此首页拿到数据后会用各应用自己的 `/api/chat/agents` **实际探测**一次——
只靠本进程注册表得出的状态必然把另外两个应用误报成「未注册」。

**顶栏全局搜索**（`/api/platform/search`）三组结果各有各的数据源，都是真的：

| 分组 | 数据源 |
|---|---|
| 应用 | 静态产品目录（平台上有哪些入口是产品事实，库里没有也不该有「开发者文档」这张表），带业务关键词——搜「转账」能命中银行助手，而不只是搜「交易型 Agent」 |
| 日志 | 查 `platform_event_log`（动作 / 明细 / 会话 / traceId 四个字段都匹配）；点进去按 traceId 精确定位那一次请求 |
| 接口 | 直接问 Spring 的 `RequestMappingHandlerMapping`，搜出来的就是真正注册了的路径 |

日志那一组只对 ADMIN 返回（与日志页同一把尺子）；非管理员仍能搜应用与接口，
不会因为权限不同整个功能就不可用。

**应用中心**支持卡片 / 列表两种视图，选择存 localStorage——用户选了列表，刷新后不该变回卡片。

---

## 可观测性

**traceId 贯穿一次请求**：每个请求由 `TraceIdFilter` 生成或采纳 `X-Trace-Id`
（只接受 16~32 位十六进制，防止往日志里注入伪造行），写入 MDC 并回写响应头。
日志格式在 platform-core 的 `logback-spring.xml` 里统一插入 `[traceId]`，一条命令即可拉全：

```bash
grep 7c8b59d5d7e64324bef41fef0570c8f7 <app.log>
```

会横跨三类线程——Servlet 请求线程、LLM 客户端的 `onPool-worker-N`、SSE 回调——
因为 SSE 的增量回调与工具调用都跑在 LangChain4j 的线程池上，MDC 不会自动跟过去。
两处显式接续：`ChatStreamController` 把回调包在 `TraceContext.runWith` 里；
工具执行由 `AgentRegistry` 统一按 memoryId 取回 traceId 重新绑定
（`TraceContext.forMemoryId`），所以 `bank_audit_log.trace_id` 与响应头严格一致。
出错时 SSE 的 `error` 事件也会带上 traceId，前端直接显示「编号 xxx」，
用户报障不需要再描述「大概什么时候、哪个客户」。

> 已知边界：一次带工具调用的对话有多轮模型调用。第一轮 token 日志带 traceId，
> 工具返回后的后续轮次由 LangChain4j 在新线程上重新发起（且 attributes 不跨轮共用），
> 拿不到 traceId。指标与审计表不受影响。

**指标**（Micrometer → `/actuator/prometheus`，需 ADMIN）：

| 指标 | 标签 | 回答什么问题 |
|---|---|---|
| `http.server.requests` | uri/method/status/outcome | 哪个接口在慢、错误率多少（自动采集） |
| `llm.calls` / `llm.duration` | model / result | 模型错误率高不高、响应多慢 |
| `llm.tokens` | model / type(input,output) | token 花在哪一头——输入是上下文成本，输出是生成成本，单价不同 |
| `chat.stream` / `chat.stream.duration` | agent / result | 对话成功率与端到端耗时 |
| `bank.tool.calls` | tool / result | 风控拒绝率是不是在涨 |
| `bank.transfer.confirm` | result | 资金操作终态（在事务之外打点，避免「指标说成功、钱没动」） |

**健康检查**：`/actuator/health`、`/actuator/health/readiness`（含 DB 检查）、
`/actuator/health/liveness`。`readiness` 带 db 而 `liveness` 不带——数据库抖动时
应该停止接入流量，而不是让进程被反复重启、放大故障。

**鉴权边界**：只有 `/actuator/health` 匿名可访问（探针不带 token，要求认证会让
readiness 永远失败、服务被判定不可用）；`/actuator/**` 其余端点要求 ADMIN。

> 坑：`WebMvcConfigurer.addInterceptors` 注册的拦截器只作用于 `RequestMappingHandlerMapping`，
> 而 Actuator 端点由独立的 `WebMvcEndpointHandlerMapping` 处理，**根本不过拦截器**——
> 在 `addPathPatterns` 里写 `/actuator/**` 是无效的（实测指标可被匿名访问）。
> 因此指标鉴权改用 `ActuatorAuthFilter`（Servlet Filter，与 HandlerMapping 无关）。

---

## LLM 成本与限流护栏

配置（`ai.guard.*`，默认值见 `LlmGuardProperties`）：

| 配置 | 默认 | 作用 |
|---|---|---|
| `enabled` | `true` | 总开关。关闭后不拦截，但**仍然记账**——先观察真实用量再定阈值更稳妥 |
| `requests-per-minute` | `20` | 单会话每分钟请求数，挡脚本与前端死循环 |
| `daily-token-limit` | `300000` | 单个服务对象每日 token 上限，挡换会话继续刷与长上下文烧额度 |

**两层维度不同，不能合并**：

- 限流按**会话**（memoryId）：正常人手速连续提问到不了 20 次/分钟，脚本一秒钟就能打出几十条。
- 预算按**计费主体**：memoryId 去掉会话段（`bank:zhangsan:7f3a…` → `bank:zhangsan`）。
  若预算也按会话算，用户新开一个会话就重置，限额等于没有。

护栏挡在**真正调用模型之前**——拦在花钱之后就没有意义。拒绝返回 HTTP 429 +
`{"message": "...", "reason": "rate_limit|token_budget"}`（与拦截器的 401/403 同形状，
前端一套解析逻辑即可），界面直接把原因显示给用户，而不是「HTTP 429」。

用量落在 `llm_usage` 表（V5 迁移），**不是**只留在内存指标里：限额要跨重启存活，且客户
投诉「为什么给我限了」时要能拿出具体数字。输入/输出 token 分列存——两者单价不同。

> 日期口径固定在数据库侧（`CURDATE()`），与转账日限额、后台统计一致。若改用 JVM 的
> `LocalDate.now()`，容器跑 UTC 时「今天」会从北京时间 08:00 才开始，每天多出 8 小时
> 窗口让配额被重复使用。

指标：`chat.guard.reject{agent,reason}` 看拒绝量与原因分布；`http.server.requests{status="429"}`
说明拒绝是传输层可见的（网关、告警都能识别）。

> 单实例限流器是进程内的，不引 Redis——多一个必须可用的外部依赖，限流器自身挂掉比限流失效
> 更严重。多实例部署时必须换成 Redis 或网关层限流，届时 `SlidingWindowRateLimiter`
> 可作本地降级实现。

---

## 技术选型

| 项 | 选型 |
|---|---|
| 基础 | JDK 21 + Spring Boot 3.5.x |
| AI 框架 | LangChain4j 1.20.x（OpenAI 兼容接入，默认 GLM glm-4.7-flash，配置化切换） |
| 存储 | MySQL 8（会话记忆 + 业务数据，容器端口 13306）+ Redis（预留） |
| 迁移 | Flyway 11（版本化 DDL，`flyway_schema_history` 记录演进，存量库自动 baseline 收敛） |
| 可观测 | Spring Boot Actuator + Micrometer（Prometheus 端点）；traceId 贯穿日志 / SSE 异步线程 / 审计表 |
| 护栏 | 自研 `ai.guard`（滑动窗口限流 + 每日 token 预算 + `llm_usage` 台账），入口处拒绝、返回 429 |
| 前端测试 | Vitest + @vue/test-utils + jsdom；CI 用 GitHub Actions（后端 MySQL service + 前端 `npm ci`） |
| 向量库 | Postgres + pgvector（知识库阶段启用） |
| 前端 | Vue3 + Vite，fetch + ReadableStream 手解 SSE 帧 |
| env | `.env` + spring-dotenv + docker compose 变量替换，一处定义两端生效 |

---

## 路线图

- [x] **Phase 0** 平台底座：LLM 配置化接入、SSE 流式、MySQL 会话记忆、Agent 注册/工具框架、token 用量日志、RAG 配置位、docker-compose
- [x] **Phase 1** 银行交易 Agent：账户/流水落库 → 转账 + human-in-the-loop 确认卡片、幂等键、审计日志、限额/白名单硬规则
- [x] **底座硬化**：测试体系（后端 104 例 + 前端 24 例）、越权与并发一致性修复（确认单按会话限定、行锁 + READ COMMITTED 串行化、日限额按执行时刻归集）、Flyway 版本化迁移、可观测性（健康检查 + 指标 + traceId 贯穿）、LLM 成本与限流护栏、CI 流水线
- [x] **工作台真实化**：首页数据全部改由数据库查询产出（新增平台运行日志表与知识库/面试领域表）、「查看日志」做成按应用筛选的真实日志页（分页/筛选/URL 可还原/traceId 回溯）、演示数据播种
- [ ] **Phase 2** 平台化：合并三应用为 `app-platform`、Spring Security 登录、工具级权限（无权限工具对模型不可见）、平台级审计中心、前端控制台布局（侧边导航 + 全局 AI 助手）、「运营助手」Agent + 只读 SQL 分析工具
- [ ] **Phase 3** 企业文档中心：真实数据源接入、路由 Agent、查询改写、混合检索（向量 + 全文）、引用溯源、评测集与回归脚本
- [ ] **Phase 4** 固化：统一部署、评测补齐、架构图 + 关键决策记录（ADR）、简历叙事

---

## 设计约定

- 各应用通过声明 `AgentSpec` Bean 注册 Agent，底座负责装配（模型/记忆/工具）
- memoryId 用 `应用名:会话ID` 形式，三个应用共用 chat_memory 表也能隔离
- 换模型只改环境变量，不改代码；RAG 组件按需装配（`ai.embedding.enabled` / `ai.rag.enabled`）
- 确定性约束（限额、权限、幂等）在代码层硬执行，不交给 LLM 自觉
- 「读宽写窄」：AI 的只读能力可以放宽，写能力必须走业务规则 + 人工确认
- **数据库是唯一事实来源**：模型看不见确认单的真实状态（过期/取消），必须通过 `queryTransferOrder()` 工具查库，不能凭对话记忆
- **结构演进靠迁移不靠补丁**：表结构变更写 Flyway 迁移并提交，禁止再用「启动时查 `information_schema` 补列」的做法——那种补丁没有版本记录，各环境会悄悄分叉
