# 多服务对象银行 AI 助手实施计划

## Context（背景）

当前银行助手把"当前登录客户是张三（62220001）"硬编码在系统提示词里，整个应用实质上只是"张三个人专属助手"。用户希望演进为**多服务对象**的银行 AI 助手：

- **零售客户**（张三）：现有全部能力（查自己账户/交易、转账）
- **内部员工**（客服专员）：只读 + 全行概况（查任意客户账户、全行统计），**禁止转账**
- **对公客户**（星辰科技）：企业账户（8 开头账号）+ 对公转账（限额更高，走现有确认卡片流程）

已确认的决策：员工只读+全行概况；对公按"企业账户+对公转账"实施；身份采用**页面内切换器**（无登录体系，localStorage 记住上次身份）。

## 方案总览：单 Agent + 会话 ID 携带身份

不注册三个 Agent（导航上"银行助手"只有一个入口，三个 Agent 会把路由和会话体系复杂化）。改为：

- **memoryId 扩展**：`bank:{uuid}` → `bank:{identityId}:{uuid}`（如 `bank:zhangsan:xxxx`）。身份随会话产生，天然实现会话隔离、权限隔离、会话列表隔离。
- **动态提示词支点**：[AgentRegistry.java](d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/agent/AgentRegistry.java) L31 的 `systemMessageProvider(memoryId -> spec.systemPrompt())` 回调天然携带 memoryId，按前缀解析身份即可注入不同提示词。
- **旧会话兼容**：`bank:{uuid}` 两段格式解析为默认身份 RETAIL。

## 后端改动

### 1. platform-core：AgentSpec 支持按会话动态提示词（唯一平台层改动）

- [AgentSpec.java](d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/agent/AgentSpec.java)：record 增加 `Function<Object,String> promptCustomizer` 组件，**保留现有构造签名**（加便捷构造委托，null = 用静态 systemPrompt），`promptFor(memoryId)` 方法：customizer 为空返回 `systemPrompt()`，否则 `customizer.apply(memoryId)`。
- [AgentRegistry.java](d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/agent/AgentRegistry.java) L31：改为 `systemMessageProvider(memoryId -> spec.promptFor(memoryId))`。knowledge/interview 不设置 customizer，行为完全不变。

### 2. app-bank：身份模型与接口

- 新建 `bank/identity/BankIdentity.java` 枚举：
  - `RETAIL("zhangsan", "零售客户·张三")` — 绑定账号 62220001
  - `STAFF("staff001", "内部员工·客服专员小陈")` — 工号 E1001，只读
  - `CORPORATE("corp001", "对公客户·星辰科技")` — 绑定对公账号 82280001
  - 含 `fromMemoryId(String)` 解析（三段取中段、两段旧格式默认 RETAIL、未知段默认 RETAIL）
  - 每个身份带展示元数据：name、role、welcome（欢迎语）、suggestions（建议问题）
- 新建 `bank/api/BankIdentityController.java`：`GET /api/bank/identities` 返回身份列表（前端选择器数据源，不硬编码）。

### 3. app-bank：动态提示词（BankAgentConfig）

- 现有提示词的"职责边界"段抽为共用常量 `COMMON_BOUNDARY`。
- `AgentSpec` 构造改为带 `promptCustomizer`：按 memoryId 解析身份，拼接 `身份上下文 + 身份专属指令 + 共用边界`：
  - **RETAIL**：现有能力描述 + "当前登录客户是张三（62220001）"
  - **STAFF**：内部客服助手视角，可查询任意客户账户与交易（需客户提供姓名/账号）、可调 bankOverview 看全行概况；**严禁转账**，用户要求转账时说明员工账号无资金操作权限
  - **CORPORATE**：对公客户星辰科技（82280001），对公转账单笔限额更高，仍走确认卡片

### 4. app-bank：工具层权限收放

- [ToolCallContext.java](d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/tool/ToolCallContext.java)：ThreadLocal 从单 memoryId 扩展为同时携带 `BankIdentity`（加 identity 字段与存取方法）。
- [BankToolProvider.java](d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/tool/BankToolProvider.java)：解析 memoryId → 身份放入 context；**按身份返回不同工具集**：STAFF 不暴露 transfer/queryTransferOrder 工具规格，暴露 bankOverview；RETAIL/CORPORATE 维持现有工具集。
- [AccountTools.java](d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/tool/AccountTools.java)：
  - `listAccounts`：RETAIL → 仅本人账户；STAFF → 全量（标注账户类型：对公/个人，按账号 8 前缀）；CORPORATE → 仅本企业账户
  - `queryAccount`：RETAIL 查非本人账号 → 拒绝并说明；STAFF → 任意；CORPORATE → 仅 8 开头本企业账号
  - 新增 `bankOverview` 工具（全行账户数、客户数、总余额）
- [TransferTools.java](d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/tool/TransferTools.java) + [TransferRiskRules.java](d:/download/project/ai-workbench/app-bank/src/main/java/com/ai/workbench/bank/risk/TransferRiskRules.java)：
  - 转出账户校验：RETAIL 必须 = 62220001；CORPORATE 必须 = 82280001（转自己之外的账户一律拒绝）
  - 限额分档：个人沿用现有限额；对公单独更高一档
  - STAFF 兜底：即使误注册 transfer 也直接拒绝

### 5. platform-core：会话列表按身份过滤

- [SessionController.java](d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/api/SessionController.java)：`GET /api/sessions/{agent}` 加**可选** query 参数 `identity`，有值时 `LIKE '{agent}:{identity}:%'`，无值维持 `'{agent}:%'`（其他两个应用不受影响）。DELETE 接口已有前缀校验，不动。

### 6. 数据造数（schema.sql，幂等）

- 企业账户：`82280001 星辰科技（对公） 余额 520000.00`
- 对公流水 3 条（货款回款、办公用品采购、员工报销代发），期初流水对齐账实（balance = 期初 + Σ流水）

## 前端改动

### 7. [chat.ts](d:/download/project/ai-workbench/frontend/src/api/chat.ts)

- `getMemoryId/newMemoryId(agent, identity?)`：identity 存在 → `${agent}:${identity}:${uuid}`
- `listSessions(basePath, agent, identity?)`：identity → `?identity=${identity}`
- 新增 `listIdentities(basePath): Promise<IdentityInfo[]>`
- localStorage：`aiwb-identity-${agent}` 记住当前身份

### 8. [ChatWindow.vue](d:/download/project/ai-workbench/frontend/src/components/ChatWindow.vue)

- 新可选 prop `enableIdentity`（BankView 传 true，knowledge/interview 不传）
- 侧栏"开启新对话"上方渲染**身份选择器**（数据来自 identities 接口）：显示当前身份，下拉切换
- 切换身份：写 localStorage → 清空消息区 → 当前 memoryId 按 `agent:identity:` 重算 → 重新拉会话列表 → 欢迎语/建议问题按身份元数据刷新
- memoryId 相关调用点（加载历史/发送/删除会话）全部透传 identity

### 9. [BankView.vue](d:/download/project/ai-workbench/frontend/src/views/BankView.vue)

- 传 `enable-identity`；默认欢迎语/建议与 RETAIL 身份一致（身份元数据会覆盖）

## 验证

1. `mvn -pl app-bank -am install -DskipTests` 编译通过；前端 `vue-tsc --noEmit` 通过
2. 启动 MySQL（已有容器）+ app-bank + Vite，浏览器验证：
   - **张三**：查自己余额 ✅；查王五余额 → 拒绝并说明只能查本人；发起转账 → 确认卡片正常
   - **员工**：查王五余额 ✅；全行概况 ✅；要求转账 → 说明无权限（工具未暴露）；会话列表与张三隔离
   - **对公**：查企业账户 ✅；对公转账 50000 → 确认卡片（对公限额档）；从张三账号转出 → 拒绝
   - 刷新页面 → 身份保持、会话列表按身份恢复
3. 回归：knowledge/interview 两个应用正常对话（platform-core 改动无影响）

## 兼容与风险

- 旧会话 `bank:{uuid}` 解析为 RETAIL 仍可对话，但新前端按身份查询后旧会话不可见（演示数据，可接受）
- AgentSpec 保留旧构造签名，三个应用无需连带修改
- 对公转账按"企业账户+对公转账+更高限额"实施；若想改为对公仅查询，只需在工具注册处去掉 CORPORATE 的 transfer，改动点集中
