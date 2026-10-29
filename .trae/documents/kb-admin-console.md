# 个人知识库企业级管理后台 实施计划

## Context

用户在个人知识库页面点右上角「管理后台」进入的是 `/admin`——那是银行域后台（审批中心/客户360/限额，接口在 app-bank 8081）。原因：[App.vue L60-64](file:///d:/download/project/ai-workbench/frontend/src/App.vue#L60-L64) 的 `onUserCommand('admin')` 写死 `router.push('/admin')`。

甲方需求：**企业级的个人知识库后台管理**。目标：`/kb-admin` 独立后台（ADMIN 专用，接口落 app-knowledge 8082），入口按当前应用分流——银行页点「管理后台」进 `/admin`，知识库页点进 `/kb-admin`，实现「不同 Agent 切换不同后台」。

**一期模块取舍**（不做过度的）：
1. **总览 Dashboard**——库数/文档数/切片数/存储量/近 7 日检索趋势（复用 BaseChart）
2. **用户库治理**——按 owner 分页搜索个人库 → 下钻该用户文档 → 管理员停用/删除文档（企业治理核心）
3. **绑定管理**——knowledge Agent 绑定哪些平台空间（打通检索的数据源，现无任何 UI 可配）
4. **检索审计**——platform_event 里 kb 工具调用记录（ToolAuditLogger 已在写，缺展示面）
5. **配额**——每人文档数/总容量上限，超出上传被拒（企业级标配，yml 可配）

**明确不做**（避免过度设计）：管理员删除整个用户库（危险操作，一期只做文档级治理）、库级禁用（status 字段无此语义）、多租户/角色细分。

## 实施步骤

### 第 1 步 后端权限：app-knowledge 拦截器加 ADMIN 判定
- [KnowledgeAuthInterceptor.java](file:///d:/download/project/ai-workbench/app-knowledge/src/main/java/com/ai/workbench/knowledge/auth/KnowledgeAuthInterceptor.java)：对 `/api/kb/admin/**` 路径额外校验 `KnowledgeUser.platformRole() == 'ADMIN'`，否则 403（KnowledgeUser 已实现 CorePrincipal 带 role，JWT 里 app-bank 签发时含 role claim）

### 第 2 步 后端管理接口：新建 KbAdminQueryService + KbAdminController（app-knowledge）
- 仿照 [BankAdminQueryService.java](file:///d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/admin/BankAdminQueryService.java) 的统计 SQL 模式与 [BankAdminController.java](file:///d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/api/BankAdminController.java) 的 controller 结构
- `KbAdminQueryService`（@Service，JdbcTemplate）：
  - `overview()`：`kb_space WHERE space_type='PERSONAL'` 聚合（库数/启用库数）、`kb_document` 聚合（文档数/FAILED 数/总 size_kb/总 words/chunk 总数）、近 7 日 `platform_event` 中 kb 工具调用按日计数（复用 BankAdminQueryService 查 platform_event 的写法）
  - `libraries(page,size,keyword)`：PERSONAL 空间 JOIN platform_user 按 owner 搜索分页
  - `documents(spaceId)`：该库文档列表
  - `setDocEnabled(docId, enabled)` / `deleteDoc(docId)`：先 JOIN 确认文档属于 PERSONAL 空间（防误伤平台库），复用 [KbDocService.removeDoc](file:///d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/kb/KbDocService.java)（级联清 pgvector）
  - `bindings()` / `bind(spaceId)` / `unbind(spaceId)`：kb_binding WHERE agent_key='knowledge'
  - `audit(page,size)`：platform_event 里 kb 检索记录分页
- `KbAdminController`（@RequestMapping("/api/kb/admin")，从 @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) 取用户）：`GET /overview`、`GET /libraries`、`GET /libraries/{spaceId}/documents`、`PUT /documents/{docId}/enabled`、`DELETE /documents/{docId}`、`GET /bindings`、`POST /bindings`、`DELETE /bindings/{spaceId}`、`GET /audit`；响应小驼峰，错误 `{"message":...}`

### 第 3 步 配额：上传校验（app-knowledge）
- `application.yml` 加 `kb.personal.max-docs: 200`、`kb.personal.max-total-mb: 512`（带中文注释：企业配额默认值，0=不限制）
- [PersonalKbController.java](file:///d:/download/project/ai-workbench/app-knowledge/src/main/java/com/ai/workbench/knowledge/api/PersonalKbController.java) 上传（文件与 URL 两条路）前校验：当前库文档数 ≥ max-docs 或累计 size_kb ≥ max-total-mb → 400「已达企业配额上限…」

### 第 4 步 前端：入口分流 + 新后台
- [App.vue](file:///d:/download/project/ai-workbench/frontend/src/App.vue)：`onUserCommand('admin')` 改为按当前路由分流——`route.path.startsWith('/knowledge') ? '/kb-admin' : '/admin'`（注释说明按应用分流的设计）
- [router/index.ts](file:///d:/download/project/ai-workbench/frontend/src/router/index.ts)：挂 `/kb-admin` 路由（KbAdminLayout + dashboard/libraries/bindings/audit 四子页）；`isAdminOnly()` 加 `/kb-admin` 前缀
- 新建 `frontend/src/api/kbAdmin.ts`：仿 [admin.ts](file:///d:/download/project/ai-workbench/frontend/src/api/admin.ts) 封装，前缀 `/knowledge/api/kb/admin`
- 新建 `frontend/src/views/kbadmin/`：
  - `KbAdminLayout.vue`——仿 [AdminLayout.vue](file:///d:/download/project/ai-workbench/frontend/src/views/admin/AdminLayout.vue)（标题「个人知识库 · 管理后台」、侧边菜单四项、返回应用入口）
  - `KbAdminDashboard.vue`——统计卡片 + 近 7 日检索趋势（复用 `components/admin/BaseChart`）
  - `KbAdminLibraries.vue`——owner 搜索 + 分页表格 → 展开行/抽屉看文档 → 停用/删除（ElMessageBox.confirm）
  - `KbAdminBindings.vue`——绑定列表 + 从平台空间（GET /knowledge/api/kb/spaces 已有接口）选择绑定/解绑
  - `KbAdminAudit.vue`——审计分页表格（时间/用户/问题/结果）
  - 样式沿用 admin 系低饱和描边风格，分页与其他页面一致（持久化到 localStorage 的既有模式照抄）

### 第 5 步 测试与回归
- app-knowledge：`KbAdminControllerIT`（仿 PersonalKbToolsIT 的 TestDb 模式）——ADMIN 可查 overview/治理文档；普通用户 403；删除 PERSONAL 文档级联清向量；配额超限 400
- 前端：kbAdmin.ts 的 vitest（403 透出、绑定增删、分页参数）
- 回归：`$env:JAVA_HOME='D:\download\code\jdkVersion\jdk21'; mvn -pl app-knowledge,platform-core,app-bank -am test`；frontend 下 `npm run verify`

## 验证路径（手动）
zhangsan(ADMIN) 在 /knowledge 页点「管理后台」→ 应进 /kb-admin 总览（看到自己的库与刚才上传的文档计数）→ 用户库治理里搜 zhangsan → 停用一个文档 → 回对话确认该文档内容不再被检索出 → 检索审计里看到刚才的提问记录；corp001(非 ADMIN) 访问 /kb-admin → 被路由守卫弹回首页，直调接口 403。

## 风险
1. JWT role claim 形态以 app-bank JwtService 实际签发为准（实现时先读）
2. platform_event 的 kb 事件 category/tool 命名要查实际落库值（PersonalKbTools 走 ToolAuditLogger，工具名 searchMyKnowledge）
3. 绑定管理操作的是平台空间 id，需校验 space_type='PLATFORM'（个人库不允许被绑给 Agent）
