<template>
  <section class="portal">
    <!-- 欢迎区：租户上下文 -->
    <div class="welcome">
      <h1>{{ greeting }}，张三</h1>
      <p class="tenant-line">
        当前租户：<b>智汇银行（总行）</b>
        <el-divider direction="vertical" />
        角色：<b>总行管理员</b>
        <el-divider direction="vertical" />
        <span class="env-tag">生产环境</span>
      </p>
    </div>

    <!-- 平台能力条：可度量的底座状态 -->
    <div class="capability-bar">
      <div v-for="c in capabilities" :key="c.name" class="capability">
        <el-icon class="cap-icon"><component :is="c.icon" /></el-icon>
        <div>
          <div class="cap-name">{{ c.name }}</div>
          <div class="cap-metric">{{ c.metric }}</div>
        </div>
      </div>
    </div>

    <!-- 应用入口卡片区 -->
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
          <el-button type="primary" size="small" class="open-btn" @click="router.push(app.to!)">打开工作台</el-button>
          <el-button size="small" link type="primary" @click="router.push('/admin/audit')">查看日志</el-button>
        </div>
      </div>

      <!-- 新建 Agent / 接入 API -->
      <div class="app-card create-card">
        <el-icon class="create-icon"><Plus /></el-icon>
        <div class="create-title">新建 Agent 应用</div>
        <div class="create-sub">用一份 AgentSpec 声明接入，或通过 API 对接已有应用</div>
        <div class="app-actions center">
          <el-button size="small" class="open-btn" @click="specVisible = true">用 JSON 注册</el-button>
          <el-button size="small" link type="primary" @click="router.push('/developers')">查看 API 文档</el-button>
        </div>
      </div>
    </div>

    <!-- 平台状态栏 -->
    <div class="platform-bar">
      <div class="pb-metrics">
        <span class="pb-item">API 调用（今日）<b>1,240</b> 次</span>
        <el-divider direction="vertical" />
        <span class="pb-item">平均延迟 <b>320ms</b></span>
        <el-divider direction="vertical" />
        <span class="pb-item">成功率 <b>99.2%</b></span>
        <el-divider direction="vertical" />
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

/** 平台底座能力：可度量状态，而非一句口号 */
const capabilities = [
  { icon: Lightning, name: 'LLM 接入', metric: '已接入 4 个模型' },
  { icon: Connection, name: 'SSE 流式', metric: '平均首字 320ms' },
  { icon: Cpu, name: '会话记忆', metric: '上下文窗口 32k' },
  { icon: SetUp, name: '工具框架', metric: '注册工具 12 个' },
]

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
    type: '交易型 Agent · 工具调用 + 流式对话',
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
    type: '检索型 Agent · 多源路由 + RAG + 引用溯源',
    status: 'building', statusText: '建设中',
    metrics: [
      { label: '文档', value: '48 篇' },
      { label: '检索 P95', value: '210ms' },
    ],
    to: '/knowledge',
  },
  {
    key: 'interview', icon: Microphone, name: '面试模拟器',
    type: '流程型 Agent · 结构化评分与评估报告',
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
.portal {
  max-width: 1200px;
  margin: 0 auto;
}

/* 欢迎区 */
.welcome { margin: 8px 0 20px; }
.welcome h1 { font-size: 24px; margin: 0 0 8px; color: #12263f; }
.tenant-line { margin: 0; color: #5a6b80; font-size: 13.5px; display: flex; align-items: center; }
.tenant-line b { color: #1f2d3d; }
.env-tag {
  font-size: 12px;
  color: #0a8f3c;
  background: #e8f7ee;
  border: 1px solid #b7e2c6;
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}

/* 能力条 */
.capability-bar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 26px;
}
.capability {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 4px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06);
}
.cap-icon { font-size: 22px; color: #0b4f9e; }
.cap-name { font-size: 13.5px; font-weight: 600; color: #1f2d3d; }
.cap-metric { font-size: 12px; color: #8a97a8; margin-top: 2px; }

/* 应用卡片区 */
.section-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 12px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 26px;
}
.app-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 4px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06);
}
.app-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.app-icon { font-size: 19px; color: #0b4f9e; flex-shrink: 0; }
.app-name {
  font-size: 14.5px;
  font-weight: 700;
  color: #1f2d3d;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
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
  font-size: 12px;
  color: #8a97a8;
  line-height: 1.6;
  margin: 8px 0 12px;
  min-height: 38px;
}
.app-metrics {
  display: flex;
  gap: 18px;
  border-top: 1px solid #eef1f5;
  padding-top: 12px;
  margin-bottom: 12px;
}
.metric { display: flex; flex-direction: column; }
.metric-value {
  font-size: 16px;
  font-weight: 700;
  color: #0b4f9e;
  font-variant-numeric: tabular-nums;
}
.metric-label { font-size: 11.5px; color: #98a2b0; margin-top: 2px; }
.app-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
}
.app-actions.center { justify-content: center; }
.open-btn { background: #0b4f9e; border-color: #0b4f9e; }

/* 新建 Agent 卡 */
.create-card {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
  border-color: #b9c8da;
  background: #fbfdff;
  gap: 6px;
}
.create-icon { font-size: 26px; color: #0b4f9e; }
.create-title { font-size: 14.5px; font-weight: 700; color: #1f2d3d; }
.create-sub { font-size: 12px; color: #8a97a8; line-height: 1.6; margin-bottom: 8px; }

/* 平台状态栏 */
.platform-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #0e2a4d;
  border-radius: 4px;
  padding: 12px 18px;
  color: #c6d5e6;
  font-size: 12.5px;
}
.pb-metrics { display: flex; align-items: center; }
.pb-item b {
  color: #fff;
  font-variant-numeric: tabular-nums;
  margin: 0 2px;
}
.platform-bar :deep(.el-divider) {
  background: rgba(255, 255, 255, 0.2);
  margin: 0 14px;
}
.pb-links :deep(.el-button) { color: #8fc0f5; }

.spec-json {
  background: #0e2a4d;
  color: #d5e4f5;
  border-radius: 4px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.7;
  overflow: auto;
  margin: 0;
}
.spec-tip { font-size: 12.5px; color: #8a97a8; margin: 10px 0 0; }

@media (max-width: 960px) {
  .capability-bar, .app-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .app-grid { grid-template-columns: 1fr; }
}
</style>
