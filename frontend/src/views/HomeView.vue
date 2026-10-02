<template>
  <section class="portal">
    <!-- 品牌英雄区：深蓝渐变呼应登录页，问候 + 玻璃能力胶囊 -->
    <div class="hero">
      <svg class="mesh" width="300" height="220" viewBox="0 0 300 220" fill="none" aria-hidden="true">
        <circle cx="36" cy="48" r="3" fill="rgba(255,255,255,.5)" />
        <circle cx="130" cy="26" r="2" fill="rgba(255,255,255,.35)" />
        <circle cx="210" cy="80" r="2.5" fill="rgba(255,255,255,.4)" />
        <circle cx="80" cy="140" r="2" fill="rgba(255,255,255,.3)" />
        <path d="M36 48 L130 26 L210 80 L80 140 Z" stroke="rgba(255,255,255,.14)" fill="none" />
      </svg>
      <div class="hero-main">
        <h1>{{ greeting }}，{{ auth.user?.displayName ?? '用户' }}</h1>
        <p class="tenant-line">
          智汇银行（总行） · {{ roleLabel(auth.user?.platformRole) }} · <span class="env-tag">生产环境</span>
        </p>
        <div class="cap-row">
          <span class="cap"><el-icon><Lightning /></el-icon>LLM 接入<b>{{ hero.models }} 个模型</b></span>
          <span class="cap"><el-icon><Connection /></el-icon>SSE 流式<b>{{ hero.sseP95Ms == null ? '—' : hero.sseP95Ms + 'ms' }}</b></span>
          <span class="cap"><el-icon><Cpu /></el-icon>会话记忆<b>{{ hero.memorySegments.toLocaleString() }} 段</b></span>
          <span class="cap"><el-icon><SetUp /></el-icon>工具能力<b>{{ hero.tools }} 项</b></span>
        </div>
      </div>
      <div class="hero-mark" aria-hidden="true">智</div>
    </div>

    <!-- 应用入口卡片区：2 × 2 大卡片撑满视口 -->
    <div class="section-title">
      应用中心
      <span v-if="loading" class="loading-hint">加载中…</span>
    </div>

    <el-alert
      v-if="error"
      class="load-error"
      type="warning"
      :title="`统计数字加载失败：${error}`"
      description="应用入口不受影响，仍可正常打开；下方指标显示为 — 表示暂未取到。"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button link type="primary" @click="load">重试</el-button>
      </template>
    </el-alert>

    <div class="app-grid">
      <div v-for="app in apps" :key="app.key" class="app-card">
        <div class="app-head">
          <span class="app-tile" :class="app.tone"><el-icon class="app-icon"><component :is="iconOf(app.key)" /></el-icon></span>
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
          <el-button type="primary" size="default" class="open-btn" @click="router.push(app.to)">打开工作台</el-button>
          <!-- 日志含会话 id / traceId / 业务明细，属运维视图，只对管理员开放 -->
          <el-button v-if="workbench?.admin" link type="primary" @click="openLogs(app.key)">查看日志</el-button>
        </div>
      </div>

      <!-- 新建 Agent / 接入 API -->
      <div class="app-card create-card">
        <el-icon class="create-icon"><Plus /></el-icon>
        <div class="create-title">新建 Agent 应用</div>
        <div class="create-sub">用一份 AgentSpec 声明接入，或通过平台 API 对接已有应用</div>
        <div class="app-actions center">
          <el-button class="outline-btn" @click="specVisible = true">用 JSON 注册</el-button>
          <el-button link type="primary" @click="router.push('/developers')">查看 API 文档</el-button>
        </div>
      </div>
    </div>

    <!-- 平台状态栏：数字全部来自运行日志表与 Spring 已注册的路由 -->
    <div class="platform-bar">
      <div class="pb-metrics">
        <span class="pb-item">平台事件（今日）<b>{{ platform.eventsToday.toLocaleString() }}</b> 次</span>
        <span class="sep">·</span>
        <span class="pb-item">平均延迟 <b>{{ platform.avgLatencyMs == null ? '—' : platform.avgLatencyMs + 'ms' }}</b></span>
        <span class="sep">·</span>
        <span class="pb-item">成功率 <b>{{ platform.successRate }}%</b></span>
        <span class="sep">·</span>
        <span class="pb-item">已注册接口 <b>{{ platform.endpoints }}</b> 个</span>
      </div>
      <div class="pb-links">
        <span class="pb-updated">数据更新于 {{ platform.updatedAt }}</span>
        <el-button size="small" class="outline-btn" @click="router.push('/developers')">开发者文档</el-button>
        <el-button v-if="workbench?.admin" size="small" class="outline-btn" @click="router.push('/logs')">查看日志</el-button>
        <el-button v-if="workbench?.admin" size="small" class="outline-btn" @click="router.push('/admin/dashboard')">平台监控</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { auth, roleLabel } from '../api/auth'
import {
  loadWorkbench, notifications, probeAppRunning,
  type AppCard, type Workbench,
} from '../api/platform'
import {
  Lightning, Connection, Cpu, SetUp, OfficeBuilding, Collection,
  Microphone, Plus,
} from '@element-plus/icons-vue'

const router = useRouter()
const specVisible = ref(false)

const workbench = ref<Workbench | null>(null)
const loading = ref(false)
const error = ref('')

/**
 * 应用清单是**产品结构**（有哪几个应用、叫什么、点进去是哪儿），不是统计结果。
 *
 * 所以它必须有兜底：接口挂了也要照常列出三个应用。原先直接用 `workbench?.apps ?? []`，
 * 结果统计接口一 404，整个应用中心就只剩"新建 Agent"一张卡——用户看到的像是
 * "知识库和面试被删了"。而那个时刻用户最需要的恰恰是能点进各个应用继续干活，
 * 统计数字晚一点到、或者显示 — 都可以接受，入口消失不行。
 *
 * 文案与后端 PlatformConsoleService.appCards() 保持一致，避免兜底态与正常态看着像两个产品。
 */
const FALLBACK_APPS: AppCard[] = [
  {
    key: 'bank', name: '银行助手「小银」', tone: 'blue', status: 'ready', statusText: '状态检测中',
    type: '交易型 Agent · 工具调用 + 流式对话，支持转账确认卡片、幂等与全程审计', to: '/bank',
    metrics: [
      { label: '今日会话', value: '—' },
      { label: '工具调用', value: '—' },
      { label: '成功率', value: '—' },
    ],
  },
  {
    key: 'knowledge', name: '个人知识库', tone: 'violet', status: 'ready', statusText: '状态检测中',
    type: '检索型 Agent · 多源路由 + RAG + 引用溯源，向量索引已就绪', to: '/knowledge',
    metrics: [
      { label: '文档', value: '—' },
      { label: '检索 P95', value: '—' },
    ],
  },
  {
    key: 'interview', name: '面试模拟器', tone: 'green', status: 'ready', statusText: '状态检测中',
    type: '流程型 Agent · 结构化评分与评估报告，支持多轮追问', to: '/interview',
    metrics: [
      { label: '累计面试', value: '—' },
      { label: '平均分', value: '—' },
    ],
  },
]

/** 在线状态单独存：探测结果要同时作用于"接口返回的列表"和"兜底列表"，
 *  直接改列表对象会把兜底常量改脏，下次复用就带上了上一轮的状态 */
const probedStatus = reactive<Record<string, { status: AppCard['status']; statusText: string }>>({})

const apps = computed<AppCard[]>(() => {
  const list = workbench.value?.apps?.length ? workbench.value.apps : FALLBACK_APPS
  return list.map(app => ({ ...app, ...(probedStatus[app.key] ?? {}) }))
})

/** 空态而不是写死的默认值：宁可显示 0 / —，也不显示一个编出来的漂亮数字 */
const EMPTY_PLATFORM = { eventsToday: 0, avgLatencyMs: null, successRate: 0, endpoints: 0, updatedAt: '—' }

const platform = computed(() => workbench.value?.platform ?? EMPTY_PLATFORM)
const hero = computed(() => workbench.value?.hero ?? {
  models: 0, sseP95Ms: null, memorySegments: 0, tools: 0,
})

async function load() {
  loading.value = true
  error.value = ''
  // 状态探测与统计接口彼此独立：统计挂了也要能看出哪些应用在线
  void probeStatuses()
  try {
    workbench.value = await loadWorkbench()
    notifications.value = workbench.value.notifications
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
    notifications.value = []
  } finally {
    loading.value = false
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
  router.push({ path: '/logs', query: { app: appKey } })
}

/** 卡片图标按 key 映射（图标是展示细节，不必走接口） */
const ICONS: Record<string, object> = {
  bank: OfficeBuilding,
  knowledge: Collection,
  interview: Microphone,
}
const iconOf = (key: string) => ICONS[key] ?? OfficeBuilding

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

onMounted(load)
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

  /* 容器驱动布局：撑满视口，消除下方空白 */
  min-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}

/* 品牌英雄区：深蓝渐变 + 星点装饰 + 右侧水印，呼应登录页 */
.hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background:
    radial-gradient(620px 300px at 85% -20%, rgba(255, 255, 255, 0.15) 0%, transparent 60%),
    linear-gradient(160deg, #0d376e 0%, #0b4f9e 55%, #1560b8 100%);
  border-radius: 10px;
  padding: 26px 32px;
  margin-bottom: 24px;
  color: #fff;
}
.hero .mesh {
  position: absolute;
  left: 0;
  top: 0;
  pointer-events: none;
  opacity: 0.8;
}
.hero h1 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 1px;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.25);
}
.tenant-line {
  margin: 0;
  color: rgba(255, 255, 255, 0.85);
  font-size: 13.5px;
}
.env-tag {
  font-size: 12px;
  color: #7ee2a8;
  background: rgba(46, 160, 90, 0.2);
  border: 1px solid rgba(126, 226, 168, 0.4);
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}
/* 能力胶囊：玻璃质感，与登录页同语言 */
.cap-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}
.cap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.95);
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 999px;
  padding: 6px 14px;
  backdrop-filter: blur(4px);
  white-space: nowrap;
}
.cap b {
  font-weight: 700;
  color: #fff;
  margin-left: 2px;
  font-variant-numeric: tabular-nums;
}
.cap .el-icon { color: #9cc4ff; font-size: 14px; }
.hero-mark {
  position: absolute;
  right: 30px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 150px;
  font-weight: 800;
  line-height: 1;
  color: rgba(255, 255, 255, 0.07);
  user-select: none;
  pointer-events: none;
}

/* 应用中心：2 × 2 大卡片，行等高、区域撑满剩余视口 */
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 14px;
}
.section-title::before {
  content: '';
  width: 4px;
  height: 16px;
  background: #0b4f9e;
  border-radius: 2px;
}
.loading-hint {
  font-size: 12px;
  font-weight: 400;
  color: #98a2b0;
  margin-left: 6px;
}
.load-error {
  margin-bottom: 14px;
}
.app-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: 16px;
  margin-bottom: 24px;
}
.app-card {
  display: flex;
  flex-direction: column;
  min-height: 260px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 18px 22px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.app-card:hover {
  transform: translateY(-3px);
  border-color: #9fc3e8;
  box-shadow: 0 10px 24px rgba(11, 79, 158, 0.12);
}
.app-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
/* 图标瓷砖：每个应用一个品牌色渐变 */
.app-tile {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
}
.app-tile .app-icon { font-size: 20px; color: #fff; }
.app-tile.blue {
  background: linear-gradient(135deg, #2f7bff, #0b4f9e);
  box-shadow: 0 4px 10px rgba(11, 79, 158, 0.25);
}
.app-tile.violet {
  background: linear-gradient(135deg, #8b5cf6, #6d28d9);
  box-shadow: 0 4px 10px rgba(109, 40, 217, 0.25);
}
.app-tile.green {
  background: linear-gradient(135deg, #10b981, #047857);
  box-shadow: 0 4px 10px rgba(4, 120, 87, 0.25);
}
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
.status.offline { color: #98a2b0; }
.status.offline .dot { background: #c0c4cc; }

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
  padding-top: 13px;
  margin-bottom: 13px;
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
  border-top: 1px solid #f0f2f6;
  padding-top: 12px;
}
.app-actions.center { justify-content: center; }
.open-btn { background: #0b4f9e; border-color: #0b4f9e; }

/* 次要操作：白底描边，与主操作区分 */
.outline-btn {
  background: #fff;
  border-color: #9fbcd9;
  color: #0b4f9e;
}
.outline-btn:hover {
  background: #f2f7fc;
  border-color: #0b4f9e;
  color: #0b4f9e;
}

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

/* 平台状态栏：浅蓝底呼应顶部上下文区，弱化视觉权重 */
.platform-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f0f5fb;
  border: 1px solid #dbe7f3;
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
.pb-links {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pb-updated {
  font-size: 11.5px;
  color: #98a2b0;
  margin-right: 4px;
  font-variant-numeric: tabular-nums;
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

@media (max-width: 860px) {
  .app-grid { grid-template-columns: 1fr; }
}
</style>
