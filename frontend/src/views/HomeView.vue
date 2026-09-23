<template>
  <section class="portal">
    <!-- 顶部上下文区：租户信息 + 平台底座能力（一行内联） -->
    <div class="context-panel">
      <h1>{{ greeting }}，张三</h1>
      <p class="tenant-line">
        智汇银行（总行） · 总行管理员 · <span class="env-tag">生产环境</span>
      </p>
      <div class="cap-row">
        <span class="cap"><el-icon><Lightning /></el-icon>LLM 接入 <b>4 个模型</b></span>
        <span class="sep">·</span>
        <span class="cap"><el-icon><Connection /></el-icon>SSE 流式 <b>平均首字 320ms</b></span>
        <span class="sep">·</span>
        <span class="cap"><el-icon><Cpu /></el-icon>会话记忆 <b>上下文 32k</b></span>
        <span class="sep">·</span>
        <span class="cap"><el-icon><SetUp /></el-icon>工具框架 <b>注册工具 12 个</b></span>
      </div>
    </div>

    <!-- 应用入口卡片区：2 × 2 大卡片撑满视口 -->
    <div class="section-title">应用中心</div>
    <div class="app-grid">
      <div v-for="app in apps" :key="app.key" class="app-card">
        <div class="app-head">
          <el-icon class="app-icon"><component :is="app.icon" /></el-icon>
          <span class="app-name">{{ app.name }}</span>
          <span class="status" :class="app.status"><i class="dot" />{{ app.statusText }}</span>
        </div>
        <div class="app-type">{{ app.type }}</div>
        <div class="app-metrics">
          <div v-for="m in app.metrics" :key="m.label" class="metric">
            <span class="metric-value">{{ m.value }}</span>
            <span class="metric-label">{{ m.label }}</span>
          </div>
        </div>
        <div class="app-actions">
          <el-button type="primary" size="default" class="open-btn" @click="router.push(app.to!)">打开工作台</el-button>
          <el-button link type="primary" @click="router.push('/admin/audit')">查看日志</el-button>
        </div>
      </div>

      <!-- 新建 Agent / 接入 API -->
      <div class="app-card create-card">
        <el-icon class="create-icon"><Plus /></el-icon>
        <div class="create-title">新建 Agent 应用</div>
        <div class="create-sub">用一份 AgentSpec 声明接入，或通过平台 API 对接已有应用</div>
        <div class="app-actions center">
          <el-button type="primary" class="open-btn" @click="specVisible = true">用 JSON 注册</el-button>
          <el-button link type="primary" @click="router.push('/developers')">查看 API 文档</el-button>
        </div>
      </div>
    </div>

    <!-- 平台状态栏：浅色弱化，主视觉留给应用卡片 -->
    <div class="platform-bar">
      <div class="pb-metrics">
        <span class="pb-item">API 调用（今日）<b>1,240</b> 次</span>
        <span class="sep">·</span>
        <span class="pb-item">平均延迟 <b>320ms</b></span>
        <span class="sep">·</span>
        <span class="pb-item">成功率 <b>99.2%</b></span>
        <span class="sep">·</span>
        <span class="pb-item">管理端接口 <b>17</b> 个</span>
      </div>
      <div class="pb-links">
        <el-button size="small" link type="primary" @click="router.push('/developers')">开发者文档</el-button>
        <el-button size="small" link type="primary" @click="router.push('/admin/dashboard')">平台监控</el-button>
      </div>
    </div>

    <!-- AgentSpec 注册示例 -->
    <el-dialog v-model="specVisible" title="AgentSpec 声明示例" width="560px">
      <pre class="spec-json">{{
`{
  "agentId": "bank-assistant",
  "name": "银行助手",
  "type": "tool-agent",
  "model": "qwen-plus",
  "systemPrompt": "你是一个严谨的银行助手...",
  "tools": [
    { "name": "query_balance",  "endpoint": "/api/tool/balance" },
    { "name": "query_transactions", "endpoint": "/api/tool/transactions" },
    { "name": "transfer",       "endpoint": "/api/tool/transfer" }
  ],
  "memory": { "type": "session", "maxTurns": 20 },
  "streaming": true
}`
      }}</pre>
      <p class="spec-tip">平台按 AgentSpec 装配模型、工具与记忆；当前版本的声明集中在 platform-core，注册 API 逐步开放。</p>
      <template #footer>
        <el-button @click="specVisible = false">关闭</el-button>
        <el-button type="primary" @click="router.push('/developers')">查看接入文档</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Lightning, Connection, Cpu, SetUp, OfficeBuilding, Collection,
  Microphone, Plus,
} from '@element-plus/icons-vue'

const router = useRouter()
const specVisible = ref(false)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

interface AppCard {
  key: string
  icon: object
  name: string
  type: string
  status: 'running' | 'building' | 'ready'
  statusText: string
  metrics: { label: string; value: string }[]
  to: string
}

const apps: AppCard[] = [
  {
    key: 'bank', icon: OfficeBuilding, name: '银行助手「小银」',
    type: '交易型 Agent · 工具调用 + 流式对话，支持转账确认卡片、幂等与全程审计',
    status: 'running', statusText: '运行中',
    metrics: [
      { label: '今日会话', value: '12' },
      { label: '工具调用', value: '48' },
      { label: '成功率', value: '98%' },
    ],
    to: '/bank',
  },
  {
    key: 'knowledge', icon: Collection, name: '个人知识库',
    type: '检索型 Agent · 多源路由 + RAG + 引用溯源，Phase 2 接入真实数据源',
    status: 'building', statusText: '建设中',
    metrics: [
      { label: '文档', value: '48 篇' },
      { label: '检索 P95', value: '210ms' },
    ],
    to: '/knowledge',
  },
  {
    key: 'interview', icon: Microphone, name: '面试模拟器',
    type: '流程型 Agent · 结构化评分与评估报告，Phase 3 加入简历 RAG',
    status: 'ready', statusText: '可对话',
    metrics: [
      { label: '累计面试', value: '3 场' },
      { label: '平均分', value: '82' },
    ],
    to: '/interview',
  },
]
</script>

<style scoped>
/* 主色统一深蓝，覆盖 Element 默认蓝紫 */
.portal {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;
}

/* 顶部上下文区：浅蓝灰底面板，租户信息 + 能力条合并 */
.context-panel {
  background: #f5f8fc;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
  padding: 20px 24px 16px;
  margin-bottom: 24px;
}
.context-panel h1 {
  font-size: 20px;
  margin: 0 0 6px;
  color: #12263f;
}
.tenant-line {
  margin: 0;
  color: #5a6b80;
  font-size: 13px;
}
.env-tag {
  font-size: 12px;
  color: #0a8f3c;
  background: #e8f7ee;
  border: 1px solid #b7e2c6;
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}
/* 能力条：一行内联，弱化为底座信息 */
.cap-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #e8edf4;
  font-size: 12.5px;
  color: #7a8798;
}
.cap {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.cap b { color: #1f2d3d; font-weight: 600; }
.cap .el-icon { color: #0b4f9e; font-size: 15px; }
.sep { color: #c3ccd8; }

/* 应用中心：2 × 2 大卡片 */
.section-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 14px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.app-card {
  display: flex;
  flex-direction: column;
  min-height: 216px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
  padding: 18px 22px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.app-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.app-icon { font-size: 21px; color: #0b4f9e; flex-shrink: 0; }
.app-name { font-size: 16px; font-weight: 700; color: #1f2d3d; flex: 1; }
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  flex-shrink: 0;
}
.status .dot { width: 7px; height: 7px; border-radius: 50%; }
.status.running { color: #0a8f3c; }
.status.running .dot { background: #0a8f3c; }
.status.building { color: #b26a00; }
.status.building .dot { background: #e6a23c; }
.status.ready { color: #0b4f9e; }
.status.ready .dot { background: #0b4f9e; }

.app-type {
  font-size: 13px;
  color: #8a97a8;
  line-height: 1.7;
  margin: 10px 0 14px;
}
.app-metrics {
  display: flex;
  gap: 48px;
  border-top: 1px solid #f0f2f6;
  padding-top: 14px;
  margin-bottom: 16px;
}
.metric { display: flex; flex-direction: column; }
.metric-value {
  font-size: 20px;
  font-weight: 700;
  color: #0b4f9e;
  font-variant-numeric: tabular-nums;
}
.metric-label { font-size: 12px; color: #98a2b0; margin-top: 3px; }
.app-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: auto;
}
.app-actions.center { justify-content: center; }
.open-btn { background: #0b4f9e; border-color: #0b4f9e; }

/* 新建 Agent 卡：虚线占位 */
.create-card {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
  border-color: #b9c8da;
  background: #fbfdff;
  gap: 8px;
}
.create-icon { font-size: 30px; color: #0b4f9e; }
.create-title { font-size: 16px; font-weight: 700; color: #1f2d3d; }
.create-sub { font-size: 12.5px; color: #8a97a8; line-height: 1.7; margin-bottom: 10px; max-width: 320px; }

/* 平台状态栏：浅色，弱化视觉权重 */
.platform-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f9fc;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
  padding: 13px 20px;
  color: #5a6b80;
  font-size: 12.5px;
}
.pb-metrics { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 14px; }
.pb-item b {
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
  margin: 0 2px;
}
.pb-links :deep(.el-button) { color: #0b4f9e; }

.spec-json {
  background: #0e2a4d;
  color: #d5e4f5;
  border-radius: 6px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.7;
  overflow: auto;
  margin: 0;
}
.spec-tip { font-size: 12.5px; color: #8a97a8; margin: 10px 0 0; }

@media (max-width: 860px) {
  .app-grid { grid-template-columns: 1fr; }
}
</style>
