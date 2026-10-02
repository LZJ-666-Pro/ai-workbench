# 银行后台管理系统实现计划

## Context

银行助手目前只有一个 AI 聊天界面，用户希望补一个企业级后台管理区（顾客管理、资金管理、数据可视化、AI 审计），让整个系统更接近真实银行信息系统。已确认选型：

- 组件库 **Element Plus**、图表 **ECharts**（均需 npm 新装）
- 四个页面：仪表盘 / 顾客管理 / 资金管理 / AI 审计日志
- **纯只读**（无开户/调账等写操作）
- 集成在现有 frontend 工程，路由 `/admin`，不建独立工程

数据基础现成：`bank_account`（5 账户，8 开头=对公）、`bank_transaction`（14 条带符号流水）、`bank_transfer_order`（AI 转账单，状态机）、`bank_audit_log`（AI 工具审计）。platform-core 不动，后端零新依赖。

## 后端（app-bank，3 个新文件）

包结构：Controller 照惯例放 `api` 包；查询与 DTO 放新建 `admin` 子包：

| 新文件 | 内容 |
|---|---|
| `app-bank/src/main/java/com/ai/workbench/bank/api/BankAdminController.java` | `@RestController @RequestMapping("/api/admin")`，全 GET 只读 |
| `app-bank/src/main/java/com/ai/workbench/bank/admin/BankAdminQueryService.java` | JdbcTemplate 直查，风格照抄 `DbBankService`（构造注入 + RowMapper lambda） |
| `app-bank/src/main/java/com/ai/workbench/bank/admin/AdminDtos.java` | record 集合（`PageResult<T>` 泛型包装 + 各视图 record） |

接口清单（前端经现有 Vite 代理 `/bank/api/admin/**` → 8081，vite.config 零改动）：

- `GET /api/admin/overview` → 总余额、账户数、个人/对公数、客户数、今日流水笔数/金额
- `GET /api/admin/accounts?keyword&type(all/personal/corporate)&page&size` → 账户分页
- `GET /api/admin/accounts/{no}/transactions?page&size` → 单账户流水
- `GET /api/admin/transactions?accountNo&direction(all/in/out)&page&size` → 全部流水分页
- `GET /api/admin/transfer-orders?status&page&size` → 转账订单，ORDER BY id DESC
- `GET /api/admin/audit-logs?result&toolName&page&size` → 审计日志分页
- `GET /api/admin/stats/balance-distribution` → `[{accountNo,owner,balance}]`（饼图）
- `GET /api/admin/stats/daily-flow?days(7/30)` → `[{date,income,expense}]`（折线，Java 补零缺口日期）

SQL 要点：对公判定沿用 `account_no LIKE '8%'`；overview 聚合参考 `DbBankService.overview()`；今日流水 `WHERE DATE(created_at)=CURDATE()`；筛选动态拼 WHERE 但值全走 `?` 占位；分页 COUNT + LIMIT/OFFSET 两条 SQL；daily-flow 用 `GROUP BY DATE(created_at)` + `SUM(CASE WHEN amount>0 ...)`。

分页响应统一形状：`{"list":[],"total":14,"page":1,"size":10}`。

## 前端（frontend）

依赖：`npm i element-plus echarts`。

- **Element Plus 全量引入**（main.ts `app.use(ElementPlus)` + 引 css）：小项目按需插件收益低，简单直接；图标用 emoji 免装 icons 包
- **ECharts 按需注册**（echarts/core + Line/Pie/Bar + Grid/Tooltip/Legend + CanvasRenderer），封装 `src/components/admin/BaseChart.vue`（props 传 option，watch 更新 + resize 监听）
- 新建 `src/api/admin.ts`：接口封装 + TS interface（PageResult 泛型）

新建文件清单：

| 文件 | 内容 |
|---|---|
| `src/views/admin/AdminLayout.vue` | el-container + 深色侧栏菜单（仪表盘/顾客管理/资金管理/AI审计）+ el-main |
| `src/views/admin/AdminDashboard.vue` | 统计卡 + 余额分布饼图 + 7/30 日收支折线 |
| `src/views/admin/AdminCustomers.vue` | 账户分页表 + 筛选；"流水"按钮开 el-drawer 看明细 |
| `src/views/admin/AdminFunds.vue` | el-tabs 双 tab（全部流水 / 转账订单），筛选 + el-pagination |
| `src/views/admin/AdminAudit.vue` | 审计日志表，result 用 el-tag 着色 |
| `src/components/admin/BaseChart.vue` | ECharts 通用封装 |

改动文件：

- `src/router/index.ts`：`/admin` 父路由挂 AdminLayout，子路由 dashboard/customers/funds/audit 懒加载，空路径重定向 dashboard
- `src/App.vue`：nav 加 `🏦 管理后台`；`isChatRoute` 前缀数组追加 `'/admin'`（管理区全宽，不走限宽布局）
- `src/main.ts`：注册 Element Plus

视觉：`.admin-layout` 作用域内覆盖 `--el-color-primary: #0b4f9e`，与银行助手企业蓝一致，不污染聊天页。

数据流统一：onMounted 拉首屏 → 筛选/翻页改 reactive query → watch 重新请求 → v-loading。

## 分阶段实施（每步独立可验）

1. 装依赖 `npm i element-plus echarts`，main.ts 注册 → dev 启动各页无报错
2. 后端 3 文件，重启 app-bank → `Invoke-WebRequest http://127.0.0.1:8081/api/admin/overview` 及各接口逐个核对 JSON（注意本机 curl 被代理劫持，用 PowerShell）
3. 前端骨架（路由 + Layout + 导航入口 + api/admin.ts）→ 浏览器 5173/admin 侧栏可切换空页
4. 依次实现仪表盘 → 顾客管理 → 资金管理 → 审计页，逐页对照接口数据；回归确认 /bank 企业蓝主题与聊天功能未受影响

## 验证

- 后端：`mvn -pl app-bank spring-boot:run` 后用 PowerShell `Invoke-WebRequest` 逐接口核对 JSON（分页 total、筛选生效、状态/类型枚举正确）
- 前端：浏览器访问 `http://localhost:5173/admin`——仪表盘图表渲染、四个页面筛选/翻页/抽屉明细、el-tag 着色；再访问 /bank 确认聊天页样式与功能回归正常
- 类型检查：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`
- 全部通过后经用户确认再提交

## 细节约定

- created_at 由前端本地化格式化展示
- 审计日志 memory_id / detail 长文本表格内截断，tooltip 或 drawer 看全文
- 空态用 el-empty
