# 个人知识库 Agent 实施计划

## Context

银行助手九项优化已全部收口。用户准备开发「个人知识库 Agent」，三个需求决策已确认：

1. **一人一库**：每个登录用户一个专属知识空间，他人不可见
2. **打通检索**：回答时同时检索「当前用户的个人库 + 平台知识库」
3. **多格式**：txt/Markdown/Word/PDF/网页等大部分格式（网页 = 给 URL 抓正文入库）

**关键探索结论**：app-knowledge 模块（独立服务，端口 8082）已存在但是 Phase 0 占位壳——只有 AgentSpec("knowledge") 的 systemPrompt 声明，无工具无检索。前端 `/knowledge` 路由、应用中心「个人知识库」卡片、vite 代理 `/knowledge/api → 8082` 全部现成。**本任务 = 在 app-knowledge 模块内实现真实能力，agent key 沿用 `knowledge`，不新建。**

**必须补的安全缺口**：8082 目前完全无鉴权（vite.config.ts 仅代理；app-knowledge 无拦截器），且 platform-core 的类被多服务共同扫描，kb 接口在 8082 上裸奔。个人库按 owner 隔离的前提是先给 app-knowledge 补认证。

## 实施步骤

### 第 1 步 依赖
- `pom.xml`（根）：properties 加 tika 版本（3.x）
- `platform-core/pom.xml`：`org.apache.tika:tika-core` + `tika-parsers-standard-package`。选 Tika 而非 PDFBox+POI+jsoup 组合：一个依赖覆盖 pdf/docx/doc/html/rtf（内部即 PDFBox/POI），调用面只有 `Tika.parse`，学习者可读性最好；网页抓取用 JDK HttpClient + Tika 解析 HTML，不需要 jsoup
- `app-knowledge/pom.xml`：`jjwt-api/impl/jackson:0.12.6`（与 app-bank 同版本）

### 第 2 步 文档解析层（platform-core）
- 新建 `platform-core/src/main/java/com/ai/workbench/core/kb/DocumentExtractor.java`：
  - `extract(byte[], ext)` → 纯文本；支持 `{txt,md,csv,pdf,doc,docx,html,htm}`；`BodyContentHandler(writeLimit=2MB)` 防大文档撑爆内存
  - `fetchUrl(String url)` → `record Extracted(String title, String text)`；HttpClient 10s 超时、5MB 上限、仅 http/https、私网/localhost 黑名单防 SSRF
- 改造 [KbController.java](file:///d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/kb/KbController.java)：`ALL_TYPES` 扩到 8 类，pdf/docx 分支改走 DocumentExtractor，删除「文本提取引擎未接入」FAILED 兜底（平台库顺带兑现 V8 注释里的第二期承诺）

### 第 3 步 V12 迁移（platform-core）
- 新建 `platform-core/src/main/resources/db/migration/V12__kb_personal_space.sql`：
  - `kb_space` 加 `space_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM'`（PLATFORM/PERSONAL）+ `INDEX idx_kbspace_owner(owner)`
  - `kb_document` 加 `source_url VARCHAR(512) NULL`（网页出处）
- **一人一库唯一性**：不新增唯一约束——个人库命名固定「我的知识库（{username}）」，username 唯一 ⇒ 天然不撞既有 `uk_kb_name`（MySQL 无部分唯一索引，避免冗余列）。种子数据 owner='admin' 走 DEFAULT 'PLATFORM'，零破坏

### 第 4 步 上传链路下沉（platform-core）
- 新建 `core/kb/KbDocService.java`：把 KbController 的 insertDocument/insertChunks/rechunkDoc/recalcSpace 抽成公共服务（内部调 TextIndexer/HybridSearchService）；新增 removeSpace 级联（chunks + pgvector 向量 + binding）
- KbController 委托 KbDocService；`list()`/`detail()` 加 `WHERE space_type='PLATFORM'`（防平台库管理页泄露他人个人库标题）

### 第 5 步 app-knowledge 认证（一人一库的安全前提）
- 新建 `app-knowledge/.../knowledge/auth/KnowledgeAuthInterceptor.java`（参照 [AuthInterceptor.java](file:///d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/auth/AuthInterceptor.java)）：验 Bearer token（jjwt，secret 读 `${JWT_SECRET:ai-workbench-dev-jwt-secret-key-0123456789abcdef}` 与 app-bank 同源）→ 回库核对 platform_user.status → 校验 `knowledge:{username}:{uuid}` 的 username 段 == token username（403 拒绝伪造）→ 塞 `CorePrincipal.ATTR_PRINCIPAL`（本地 record 实现 platform-core 的 [CorePrincipal.java](file:///d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/auth/CorePrincipal.java) 接口）
- 新建 `knowledge/config/WebConfig.java` 注册拦截器；`application.yml` 加 `auth.jwt.secret` + `ai.embedding/ai.rag` 开启项（复用 app-bank 同款配置：DashScope embedding + pgvector 15432）

### 第 6 步 个人库 API（app-knowledge）
- 新建 `knowledge/api/PersonalKbController.java`（前缀 `/api/kb/personal`，全部做 owner=principal.username() 归属校验）：
  - `GET /me`：查 owner + space_type='PERSONAL'，无则懒创建「我的知识库（{username}）」（撞 uk_kb_name 时回查复用）
  - `GET /me/documents` / `POST /me/documents`（multipart，走 DocumentExtractor + KbDocService）/ `POST /me/documents/url`（JSON {url}，doc_type='html' 记 source_url）/ `PUT/DELETE /me/documents/{docId}`（先查文档所属 space 的 owner 是本人，级联 hybrid.removeDoc 清向量）
- 新建 `frontend/src/api/personalKb.ts`：前缀 `/knowledge/api/kb/personal`（现有 kb.ts 前缀固定 /bank，不混用）

### 第 7 步 Agent 工具与装配（app-knowledge）
- 新建 `knowledge/tool/KnowledgeToolContext.java`（ThreadLocal<String username>）+ `KnowledgeToolProvider.java`（镜像 BankToolProvider：executor 前后 set/clear，username 取 memoryId 第 2 段）。ToolCallContext 锁在 app-bank 且携带 BankIdentity，不跨模块复用
- 新建 `knowledge/tool/PersonalKbTools.java`：`@Tool searchMyKnowledge(question)`——检索范围 = 个人空间 id ∪ `kb_binding WHERE agent_key='knowledge'`；kb_chunk JOIN kb_document 取 candidates（照抄 [KnowledgeTools.java](file:///d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/tool/KnowledgeTools.java) 的查询模式），`hybrid.search(..., Set.copyOf(spaceIds), 4)`；出处标注「[n]《标题》（个人库/平台库）」，查不到如实说
- 改 [KnowledgeAgentConfig.java](file:///d:/download/project/ai-workbench/app-knowledge/src/main/java/com/ai/workbench/knowledge/config/KnowledgeAgentConfig.java)：AgentSpec 加 toolProvider；systemPrompt 去掉 Phase 0 占位声明，写清知识类问题必须先调工具、注明出处、检索不到不编造

### 第 8 步 前端
- 改 [KnowledgeView.vue](file:///d:/download/project/ai-workbench/frontend/src/views/KnowledgeView.vue)（现状仅 13 行占位）：`<ChatWindow base-path="/knowledge" agent="knowledge" :identity="auth.user?.username">`（memoryId 自动成 `knowledge:{username}:{uuid}`）；更新标题/欢迎语；页头加「对话 / 我的文档」Tab——文档 Tab 内嵌：文件多选上传 + URL 输入框 + 文档列表（状态徽章复用 kb.ts 的 DOC_STATUS_META，样式仿 KbDetailView 简洁表格）。路由不动
- 改 [ChatWindow.vue](file:///d:/download/project/ai-workbench/frontend/src/components/ChatWindow.vue)：TOOL_LABELS 加 `searchMyKnowledge: '正在检索你的知识库'`

### 第 9 步 测试与回归
- platform-core：`DocumentExtractorTest`（txt/md/html 提取、2MB 截断、不支持类型）、KbController 平台库过滤回归
- app-knowledge：`KnowledgeAuthInterceptorTest`（无 token 401 / 伪造 username 段 403）、`PersonalKbToolsIT`（连 MySQL 测试库测检索范围：个人库命中 + 平台绑定库命中 + 他人库不可见）
- 回归命令：`$env:JAVA_HOME='D:\download\code\jdkVersion\jdk21'; mvn -pl app-knowledge,platform-core -am test`；`frontend` 下 `npm run verify`

## 风险与对策
1. Tika 体积/内存：writeLimit 2MB + 保留 20MB 文件上限；同步解析沿用 MVP 边界
2. 扫描版 PDF 提不出字：如实标 FAILED + error_msg，不造假
3. URL 抓取 SSRF：仅 http/https + 私网黑名单 + 5MB/10s 上限
4. 删除级联：KbDocService 删文档同步清 pgvector，防已删内容从向量路残留召回
5. memoryId 含 username 仅是展示层隔离，真正防线是拦截器校验 username 段与 token 一致

## 验证路径（手动）
登录 zhangsan → /knowledge → 「我的文档」Tab 传 txt/md/docx/pdf 各一个 + 抓一个网页 → 回对话问文档内容 → 回答带出处；切 corp001 登录 → 看不到 zhangsan 的文档（隔离）；平台知识库绑定给 knowledge 后问题可同时命中平台语料。
