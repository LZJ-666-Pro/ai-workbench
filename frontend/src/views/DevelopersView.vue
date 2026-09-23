<template>
  <section class="dev-page">
    <div class="page-head">
      <h1>开发者 · 平台 API</h1>
      <p class="sub">platform-core 与业务模块对外暴露的接口。对话走 SSE 流式，管理端以只读查询为主。</p>
    </div>

    <el-card shadow="never" class="block">
      <template #header><b>会话与对话 API（platform-core）</b></template>
      <el-table :data="chatApis" size="small" stripe>
        <el-table-column prop="method" label="方法" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.method === 'GET' ? 'success' : 'primary'">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="端点" min-width="280">
          <template #default="{ row }"><code class="path">{{ row.path }}</code></template>
        </el-table-column>
        <el-table-column prop="desc" label="说明" min-width="320" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><b>业务 API（app-bank）</b></template>
      <el-table :data="bankApis" size="small" stripe>
        <el-table-column prop="method" label="方法" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.method === 'GET' ? 'success' : 'primary'">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="端点" min-width="280">
          <template #default="{ row }"><code class="path">{{ row.path }}</code></template>
        </el-table-column>
        <el-table-column prop="desc" label="说明" min-width="320" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><b>快速开始 · curl 示例</b></template>
      <pre class="code-block">{{
`# 1. 查看已注册的 Agent
curl http://localhost:8081/api/chat/agents

# 2. 发起流式对话（SSE，agentId 见上一步返回）
curl -N "http://localhost:8081/api/chat/bank/stream?message=%E6%9F%A5%E4%B8%80%E4%B8%8B%E5%BC%A0%E4%B8%89%E7%9A%84%E4%BD%99%E9%A2%9D&memoryId=bank:retail:demo-001"

# 3. 确认一笔待审批转账（HITL 第二段，CAS 幂等）
curl -X POST http://localhost:8081/api/transfer/confirm \\
  -H "Content-Type: application/json" \\
  -d '{"confirmId":"<confirmId>","memoryId":"bank:retail:demo-001","approve":true}'

# 4. 管理端查询（示例：审批看板）
curl http://localhost:8081/api/admin/approvals`
      }}</pre>
    </el-card>

    <el-card shadow="never">
      <template #header><b>接入说明</b></template>
      <ul class="notes">
        <li>每个 Agent 应用 = 一份 <b>AgentSpec</b> 声明（模型、系统提示词、工具列表、记忆策略），由 platform-core 统一装配，当前版本的声明集中在 platform-core 模块。</li>
        <li>对话接口统一为 <code class="path">GET /api/chat/{agent}/stream</code>，返回 <code class="path">text/event-stream</code>；前端只换 agentId 即可接入新应用。</li>
        <li>工具扩展：在 AgentSpec 中声明工具名与端点，平台调用后把结果回喂给模型；资金类工具必须经过风控规则与确认状态机。</li>
        <li>OpenAPI 3.0 文档（springdoc / Swagger UI）接入预留中，引入依赖后自动暴露 <code class="path">/v3/api-docs</code>。</li>
      </ul>
    </el-card>
  </section>
</template>

<script setup lang="ts">
const chatApis = [
  { method: 'GET', path: '/api/chat/agents', desc: '已注册的 Agent 应用列表（agentId、名称、类型）' },
  { method: 'GET', path: '/api/chat/{agent}/stream', desc: 'SSE 流式对话；query 参数 message、memoryId，返回 text/event-stream' },
]

const bankApis = [
  { method: 'POST', path: '/api/transfer/confirm', desc: '转账确认/取消（HITL 第二段）：CAS 状态机幂等，超时确认自动拒绝' },
  { method: 'GET', path: '/api/bank/identities', desc: '服务对象身份列表（零售客户 / 内部员工 / 对公客户）' },
  { method: 'GET', path: '/api/admin/overview', desc: '管理端概览统计：余额、账户构成、今日/昨日流水对比' },
  { method: 'GET', path: '/api/admin/customers', desc: '客户列表（按户名聚合名下账户）' },
  { method: 'GET', path: '/api/admin/customers/{owner}/profile', desc: '客户 360 视图：账户、收支、订单、AI 操作轨迹' },
  { method: 'GET', path: '/api/admin/approvals', desc: '审批看板：状态计数 + 待审批队列' },
  { method: 'GET', path: '/api/admin/transfer-orders/{id}/journey', desc: '交易旅程：订单从创建到落地的完整时间线' },
  { method: 'POST', path: '/api/admin/transfer-orders/{id}/decision?approve=', desc: '管理端审批：通过=执行划款，驳回=取消；二次确认后调用' },
  { method: 'GET', path: '/api/admin/limits', desc: '限额包：身份分档额度 + 风控硬规则' },
  { method: 'GET', path: '/api/admin/audit-logs', desc: '审计日志分页（result、toolName 筛选）' },
]
</script>

<style scoped>
.dev-page {
  max-width: 1100px;
  margin: 0 auto;
}
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 22px; margin: 0 0 6px; color: #12263f; }
.sub { margin: 0; color: #8a97a8; font-size: 13px; }
.block { margin-bottom: 14px; }
.path {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  background: #f2f5f9;
  border: 1px solid #e5e8ec;
  border-radius: 3px;
  padding: 1px 6px;
  color: #0b4f9e;
}
.code-block {
  background: #0e2a4d;
  color: #d5e4f5;
  border-radius: 4px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.8;
  overflow: auto;
  margin: 0;
}
.notes { margin: 0; padding-left: 18px; color: #4a5a6d; font-size: 13px; line-height: 2; }
</style>
