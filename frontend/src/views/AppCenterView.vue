<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Grid, Menu, OfficeBuilding, Collection, Microphone, Plus,
  Document, ArrowRight, Tickets,
} from '@element-plus/icons-vue'
import { loadWorkbench, probeAppRunning, type AppCard, type Workbench } from '../api/platform'
import ContextBar from '../components/ContextBar.vue'
import PlatformBar from '../components/PlatformBar.vue'

const router = useRouter()
const specVisible = ref(false)

const workbench = ref<Workbench | null>(null)
const error = ref('')

/** 视图偏好存本地：用户选了列表，刷新后不该变回卡片 */
const VIEW_KEY = 'aiwb-app-view'
const view = ref<'grid' | 'list'>((localStorage.getItem(VIEW_KEY) as 'grid' | 'list') || 'grid')

function setView(next: 'grid' | 'list') {
  view.value = next
  localStorage.setItem(VIEW_KEY, next)
}

/**
 * 应用清单是**产品结构**（有哪几个应用、叫什么、点进去是哪儿），不是统计结果，
 * 必须有兜底：接口挂了也要照常列出三个应用——入口消失比指标显示 — 严重得多。
 * 文案与后端 PlatformConsoleService.appCards() 保持一致。
 */
const FALLBACK_APPS: AppCard[] = [
  {
    key: 'bank', name: '银行助手「小银」', tone: 'green', status: 'ready', statusText: '状态检测中',
    type: '面向个人与小微企业的智能交易助手 Agent，支持账户查询、转账、交易确认与风险提醒。', to: '/bank',
    metrics: [
      { label: '今日会话', value: '—' },
      { label: '工具调用', value: '—' },
      { label: '成功率', value: '—' },
    ],
  },
  {
    key: 'knowledge', name: '个人知识库', tone: 'blue', status: 'ready', statusText: '状态检测中',
    type: '基于 RAG 的企业内部知识问答应用，支持文档检索、引用溯源与权限控制。', to: '/knowledge',
    metrics: [
      { label: '文档', value: '—' },
      { label: '检索 P95', value: '—' },
    ],
  },
  {
    key: 'interview', name: '面试模拟器', tone: 'violet', status: 'ready', statusText: '状态检测中',
    type: '智能面试过程型 Agent，模拟真实面试场景，提供综合评分与建议反馈。', to: '/interview',
    metrics: [
      { label: '模拟场次', value: '—' },
      { label: '平均分', value: '—' },
    ],
  },
]

/** 在线状态单独存：探测结果要同时作用于"接口返回的列表"和"兜底列表"，
 *  直接改列表对象会把兜底常量改脏，下次复用就带上了上一轮的状态 */
const probedStatus = reactive<Record<string, { status: AppCard['status']; statusText: string }>>({})

const apps = computed<AppCard[]>(() => {
  const list = workbench.value?.apps?.length ? workbench.value.apps : FALLBACK_APPS
  return list.map(app => ({
    ...app,
    // 色调按原型统一：银行=绿、知识库=蓝、面试=紫（后端返回的 tone 不再依赖）
    tone: TONE_OVERRIDE[app.key] ?? app.tone,
    ...(probedStatus[app.key] ?? {}),
  }))
})

const TONE_OVERRIDE: Record<string, AppCard['tone']> = {
  bank: 'green',
  knowledge: 'blue',
  interview: 'violet',
}

const EMPTY_PLATFORM = {
  eventsToday: 0, avgLatencyMs: null, successRate: 0, endpoints: 0,
  apps: 0, sessionsToday: 0, tokensToday: 0, updatedAt: '—',
}

const platform = computed(() => workbench.value?.platform ?? EMPTY_PLATFORM)
const hero = computed(() => workbench.value?.hero ?? {
  models: 0, sseP95Ms: null, memorySegments: 0, tools: 0,
})

/** 运行中应用数取实测探测结果；探测未完成前为 0，完成后自动刷新 */
const runningCount = computed(() => apps.value.filter(a => a.status === 'running').length)

async function load() {
  error.value = ''
  // 状态探测与统计接口彼此独立：统计挂了也要能看出哪些应用在线
  void probeStatuses()
  try {
    workbench.value = await loadWorkbench()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
  }
}

async function probeStatuses() {
  const list = workbench.value?.apps?.length ? workbench.value.apps : FALLBACK_APPS
  await Promise.all(list.map(async app => {
    const running = await probeAppRunning(app.to, app.key)
    probedStatus[app.key] = running
      ? { status: 'running', statusText: '运行中' }
      : { status: 'offline', statusText: '未启动' }
  }))
}

function openLogs(appKey: string) {
  // 平台运行日志页（/logs）含会话 id、traceId 与业务明细，仅 ADMIN 可进
  router.push({ path: '/logs', query: { app: appKey } })
}

/** 卡片图标按 key 映射（图标是展示细节，不必走接口） */
const ICONS: Record<string, object> = {
  bank: OfficeBuilding,
  knowledge: Collection,
  interview: Microphone,
}
const iconOf = (key: string) => ICONS[key] ?? OfficeBuilding

onMounted(load)
</script>

<template>
  <section class="center-page">
    <ContextBar :hero="hero" />

    <div class="page-body">
      <div class="section-head">
        <div class="head-text">
          <h2 class="section-title">应用中心</h2>
          <p class="section-sub">管理与监控您的 AI Agent 应用，快速访问与运维。</p>
        </div>
        <div class="head-tools">
          <el-button v-if="view === 'list'" type="primary" @click="specVisible = true">
            <el-icon class="btn-icon"><Plus /></el-icon>新建 Agent 应用
          </el-button>
          <div class="view-toggle">
            <button :class="{ active: view === 'grid' }" title="卡片视图" @click="setView('grid')">
              <el-icon><Grid /></el-icon>卡片
            </button>
            <button :class="{ active: view === 'list' }" title="列表视图" @click="setView('list')">
              <el-icon><Menu /></el-icon>列表
            </button>
          </div>
        </div>
      </div>

      <el-alert
        v-if="error"
        class="load-error"
        type="warning"
        :title="`统计数字加载失败：${error}`"
        description="应用入口不受影响，仍可正常打开；指标显示 — 表示暂未取到。"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>

      <!-- 卡片视图 -->
      <div v-if="view === 'grid'" class="app-grid">
        <div v-for="app in apps" :key="app.key" class="app-card">
          <div class="app-head">
            <span class="app-tile" :class="app.tone">
              <el-icon><component :is="iconOf(app.key)" /></el-icon>
            </span>
            <div class="app-head-main">
              <div class="app-title-row">
                <span class="app-name">{{ app.name }}</span>
                <span class="status" :class="app.status"><i class="dot" />{{ app.statusText }}</span>
              </div>
              <div class="app-type">{{ app.type }}</div>
            </div>
          </div>
          <div class="metrics-row">
            <div v-for="m in app.metrics" :key="m.label" class="metric">
              <span class="metric-value">{{ m.value }}</span>
              <span class="metric-label">{{ m.label }}</span>
            </div>
          </div>
          <div class="app-actions">
            <el-button class="outline-btn open-btn" @click="router.push(app.to)">
              打开工作台<el-icon class="btn-icon"><ArrowRight /></el-icon>
            </el-button>
            <!-- 日志含会话 id / traceId / 业务明细，属运维视图，只对管理员开放 -->
            <el-button v-if="workbench?.admin" class="outline-btn log-btn" @click="openLogs(app.key)">
              <el-icon class="btn-icon"><Document /></el-icon>查看日志
            </el-button>
          </div>
        </div>

        <div class="app-card create-card" @click="specVisible = true">
          <span class="create-plus"><el-icon><Plus /></el-icon></span>
          <div class="create-title">新建 Agent 应用</div>
          <div class="create-sub">快速创建新的 AI Agent，扩展你的工作台能力。</div>
          <div class="app-actions center">
            <el-button class="outline-btn" @click.stop="specVisible = true">
              <el-icon class="btn-icon"><Tickets /></el-icon>用 JSON 注册
            </el-button>
            <el-button class="outline-btn" @click.stop="router.push('/developers')">
              <el-icon class="btn-icon"><Document /></el-icon>查看 API 文档
            </el-button>
          </div>
        </div>
      </div>

      <!-- 列表视图：grid 自排行式布局（el-table 的边框/表头底色拧不过来） -->
      <div v-else class="app-list">
        <div class="list-head">
          <span>应用</span>
          <span>指标</span>
          <span>状态</span>
          <span class="ta-right">操作</span>
        </div>

        <div v-for="app in apps" :key="app.key" class="list-row">
          <div class="cell-app">
            <span class="app-tile solid" :class="app.tone">
              <el-icon><component :is="iconOf(app.key)" /></el-icon>
            </span>
            <div class="cell-app-main">
              <div class="app-name">{{ app.name }}</div>
              <div class="app-desc">{{ app.type }}</div>
            </div>
          </div>
          <div class="cell-metrics">
            <template v-for="(m, i) in app.metrics" :key="m.label">
              <span v-if="i" class="cm-sep">·</span>
              <span class="cm"><b>{{ m.value }}</b>{{ m.label }}</span>
            </template>
          </div>
          <div class="cell-status">
            <span class="badge" :class="app.status">{{ app.statusText }}</span>
          </div>
          <div class="cell-actions">
            <el-button class="outline-btn" @click="router.push(app.to)">打开工作台</el-button>
            <el-button v-if="workbench?.admin" link type="primary" @click="openLogs(app.key)">日志</el-button>
          </div>
        </div>

        <div v-if="!apps.length" class="list-empty"><el-empty description="暂无应用" /></div>
      </div>
    </div>

    <PlatformBar :platform="platform" :running-count="runningCount" />

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

<style scoped>
.center-page {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;

  min-height: calc(100vh - 56px);
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

.page-body {
  flex: 1;
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 22px 24px 26px;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.head-text { min-width: 0; }
.section-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #1f2d3d;
  letter-spacing: 0.5px;
}
.section-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8a97a8;
}
.head-tools {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

/* 卡片 / 列表 切换：描边分段控件，选中项实心蓝 */
.view-toggle {
  display: inline-flex;
  background: #fff;
  border: 1px solid #d9e0e8;
  border-radius: 8px;
  overflow: hidden;
}
.view-toggle button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: #4a5a6d;
  background: #fff;
  border: 0;
  border-radius: 0;
  padding: 8px 16px;
  cursor: pointer;
}
.view-toggle button + button {
  border-left: 1px solid #d9e0e8;
}
.view-toggle button.active {
  background: #0b4f9e;
  color: #fff;
  font-weight: 600;
}
.head-tools .el-button .btn-icon {
  margin: 0 6px 0 0;
}

.load-error { margin-bottom: 16px; }

/* 卡片网格：grid-auto-rows: 1fr 让同一行的卡片等高 */
.app-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: 16px;
}
.app-card {
  display: flex;
  flex-direction: column;
  min-height: 212px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 20px 22px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.app-card:hover {
  transform: translateY(-2px);
  border-color: #9fc3e8;
  box-shadow: 0 10px 24px rgba(11, 79, 158, 0.1);
}
.app-head {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.app-head-main {
  flex: 1;
  min-width: 0;
}
.app-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.app-tile {
  width: 44px;
  height: 44px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 22px;
}
.app-tile.blue { background: linear-gradient(135deg, #e3eefb, #d2e4f7); color: #0b4f9e; }
.app-tile.violet { background: linear-gradient(135deg, #efe8fd, #e2d8fa); color: #6d28d9; }
.app-tile.green { background: linear-gradient(135deg, #e2f6ed, #d0efe0); color: #047857; }
.app-tile.solid { color: #fff; }
.app-tile.solid.blue { background: linear-gradient(135deg, #4a90e2, #0b4f9e); }
.app-tile.solid.violet { background: linear-gradient(135deg, #a78bfa, #6d28d9); }
.app-tile.solid.green { background: linear-gradient(135deg, #34d399, #047857); }
.app-name {
  font-size: 17px;
  font-weight: 700;
  color: #1f2d3d;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 5px;
  padding: 2px 9px;
  flex-shrink: 0;
  white-space: nowrap;
}
.status .dot { width: 6px; height: 6px; border-radius: 50%; }
.status.running { color: #0a8f3c; background: #f0faf4; border: 1px solid #b7e2c6; }
.status.running .dot { background: #0a8f3c; }
.status.ready { color: #0b4f9e; background: #f0f6fc; border: 1px solid #c3daf0; }
.status.ready .dot { background: #0b4f9e; }
.status.offline { color: #8a97a8; background: #f5f6f8; border: 1px solid #dfe3e9; }
.status.offline .dot { background: #b6bcc6; }

.app-type {
  font-size: 13px;
  color: #8a97a8;
  line-height: 1.7;
  margin: 8px 0 18px;
  min-height: 44px;
}

/* 指标：数字在上、标签在下；margin-top:auto 把指标与按钮推到卡底 */
.metrics-row {
  display: flex;
  gap: 48px;
  border-top: 1px solid #f0f2f6;
  padding-top: 14px;
  margin-top: auto;
  margin-bottom: 16px;
}
.metric {
  display: flex;
  flex-direction: column;
}
.metric-value {
  font-size: 25px;
  font-weight: 700;
  color: #1f2d3d;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.metric-label {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 3px;
}

.app-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.app-actions.center { justify-content: center; }
.app-actions .el-button {
  flex: 1;
  margin: 0;
  min-width: 0;
}
.app-actions .el-button + .el-button {
  margin-left: 10px;
}
.btn-icon {
  margin-left: 5px;
  font-size: 13px;
}
.outline-btn .btn-icon {
  margin-left: 0;
  margin-right: 5px;
}

.outline-btn {
  background: #fff;
  border-color: #b9cee3;
  color: #0b4f9e;
}
.outline-btn:hover {
  background: #f2f7fc;
  border-color: #0b4f9e;
  color: #0b4f9e;
}

.create-card {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
  border-color: #b9c8da;
  background: #fbfdff;
  gap: 4px;
  cursor: pointer;
}
.create-plus {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: #0b4f9e;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  margin-bottom: 8px;
}
.create-title { font-size: 17px; font-weight: 700; color: #1f2d3d; }
.create-sub { font-size: 12.5px; color: #8a97a8; line-height: 1.7; margin-bottom: 14px; max-width: 320px; }

/* 列表视图 */
.app-list {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.list-head,
.list-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px 118px 208px;
  align-items: center;
  gap: 16px;
  padding-left: 22px;
  padding-right: 22px;
}
.list-head {
  height: 46px;
  font-size: 12.5px;
  color: #8a97a8;
  background: #fafbfc;
  border-bottom: 1px solid #eceff3;
}
.list-head .ta-right {
  text-align: right;
}
.list-row {
  padding-top: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #f2f4f7;
}
.list-row:last-child {
  border-bottom: 0;
}
.list-row:hover {
  background: #fafcfe;
}
.list-empty {
  padding: 24px 0;
}

.cell-app {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  min-width: 0;
}
.cell-app-main {
  min-width: 0;
}
.cell-app-main .app-name {
  font-size: 15.5px;
  font-weight: 700;
  color: #1f2d3d;
}
.app-desc {
  font-size: 12.5px;
  color: #8a97a8;
  line-height: 1.65;
  margin-top: 5px;
}

.cell-metrics {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 6px;
}
.cm {
  font-size: 12px;
  color: #98a2b0;
  white-space: nowrap;
}
.cm b {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
}
.cm-sep {
  color: #c8ced8;
}

.badge {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  font-weight: 600;
  border-radius: 6px;
  padding: 4px 12px;
  white-space: nowrap;
}
.badge.running { color: #0a8f3c; background: #eaf8f0; }
.badge.ready { color: #0b4f9e; background: #eaf2fb; }
.badge.offline { color: #8a97a8; background: #f0f2f5; }

.cell-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

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

@media (max-width: 900px) {
  .app-grid { grid-template-columns: 1fr; }
}
</style>
