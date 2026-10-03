<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { OfficeBuilding, Collection, Microphone, ArrowRight } from '@element-plus/icons-vue'
import { auth, roleLabel } from '../api/auth'
import {
  loadWorkbench, notifications, probeAppRunning,
  type AppCard, type Workbench,
} from '../api/platform'
import ContextBar from '../components/ContextBar.vue'
import PlatformBar from '../components/PlatformBar.vue'

const router = useRouter()

const workbench = ref<Workbench | null>(null)

const EMPTY_PLATFORM = {
  eventsToday: 0, avgLatencyMs: null, successRate: 0, endpoints: 0,
  apps: 0, sessionsToday: 0, tokensToday: 0, updatedAt: '—',
}

const platform = computed(() => workbench.value?.platform ?? EMPTY_PLATFORM)
const hero = computed(() => workbench.value?.hero ?? {
  models: 0, sseP95Ms: null, memorySegments: 0, tools: 0,
})

/** 工作台用兜底清单展示快捷入口（入口不能因接口失败而消失） */
const FALLBACK_APPS: AppCard[] = [
  { key: 'bank', name: '银行助手「小银」', tone: 'green', status: 'ready', statusText: '状态检测中', type: '交易型 Agent', to: '/bank', metrics: [] },
  { key: 'knowledge', name: '个人知识库', tone: 'blue', status: 'ready', statusText: '状态检测中', type: '检索型 Agent', to: '/knowledge', metrics: [] },
  { key: 'interview', name: '面试模拟器', tone: 'violet', status: 'ready', statusText: '状态检测中', type: '流程型 Agent', to: '/interview', metrics: [] },
]

const probedStatus = reactive<Record<string, { status: AppCard['status']; statusText: string }>>({})

const apps = computed<AppCard[]>(() => {
  const list = workbench.value?.apps?.length ? workbench.value.apps : FALLBACK_APPS
  return list.map(app => ({
    ...app,
    tone: TONE_OVERRIDE[app.key] ?? app.tone,
    ...(probedStatus[app.key] ?? {}),
  }))
})

const TONE_OVERRIDE: Record<string, AppCard['tone']> = {
  bank: 'green',
  knowledge: 'blue',
  interview: 'violet',
}

const runningCount = computed(() => apps.value.filter(a => a.status === 'running').length)

/** 今日概览四卡：数字全部来自接口，空态显示 0 / —，不编数字 */
const overview = computed(() => [
  { label: '今日总会话', value: platform.value.sessionsToday.toLocaleString(), hint: '全部应用的对话轮次' },
  { label: '今日工具调用', value: platform.value.eventsToday.toLocaleString(), hint: 'Agent 触发的业务操作' },
  { label: '平均成功率', value: `${platform.value.successRate}%`, hint: '按今日平台事件计算' },
  { label: '运行中应用', value: `${runningCount.value} / ${apps.value.length}`, hint: '在线状态实时探测' },
])

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
})

/** 快捷入口的图标与描述（应用中心承载完整卡片） */
const APP_META: Record<string, { icon: object; desc: string }> = {
  bank: { icon: OfficeBuilding, desc: '账户查询 · 转账办理 · 风险提醒' },
  knowledge: { icon: Collection, desc: '文档问答 · 引用溯源 · 权限控制' },
  interview: { icon: Microphone, desc: '模拟面试 · 综合评分 · 建议反馈' },
}
const metaOf = (key: string) => APP_META[key] ?? { icon: OfficeBuilding, desc: '' }

onMounted(async () => {
  void probeStatuses()
  try {
    workbench.value = await loadWorkbench()
  } catch {
    /* 工作台全部数字都有空态兜底，静默降级即可 */
  }
})

async function probeStatuses() {
  const list = workbench.value?.apps?.length ? workbench.value.apps : FALLBACK_APPS
  await Promise.all(list.map(async app => {
    const running = await probeAppRunning(app.to, app.key)
    probedStatus[app.key] = running
      ? { status: 'running', statusText: '运行中' }
      : { status: 'offline', statusText: '未启动' }
  }))
}
</script>

<template>
  <section class="portal">
    <ContextBar :hero="hero" />

    <div class="page-body">
      <!-- 问候区：紧凑型（应用中心承载完整卡片区，这里只做入口与概览） -->
      <div class="hello">
        <h1 class="hello-title">
          {{ greeting }}，{{ auth.user?.displayName ?? '访客' }}
          <span class="hello-role">{{ roleLabel(auth.user?.platformRole) }}</span>
        </h1>
        <p class="hello-sub">{{ today }} · 从下面的入口开始今天的工作。</p>
      </div>

      <!-- 今日概览 -->
      <div class="ov-grid">
        <div v-for="o in overview" :key="o.label" class="ov-card">
          <span class="ov-value">{{ o.value }}</span>
          <span class="ov-label">{{ o.label }}</span>
          <span class="ov-hint">{{ o.hint }}</span>
        </div>
      </div>

      <div class="two-col">
        <!-- 快捷入口 -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">快捷入口</span>
            <el-button link type="primary" @click="router.push('/apps')">查看全部<el-icon class="go-icon"><ArrowRight /></el-icon></el-button>
          </div>
          <div v-for="app in apps" :key="app.key" class="entry" @click="router.push(app.to)">
            <span class="app-tile" :class="app.tone">
              <el-icon><component :is="metaOf(app.key).icon" /></el-icon>
            </span>
            <div class="entry-main">
              <div class="entry-name">{{ app.name }}</div>
              <div class="entry-desc">{{ metaOf(app.key).desc }}</div>
            </div>
            <span class="status" :class="app.status"><i class="dot" />{{ app.statusText }}</span>
            <el-icon class="entry-go"><ArrowRight /></el-icon>
          </div>
        </div>

        <!-- 待办通知 -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">待办与通知</span>
          </div>
          <div v-if="!notifications.length" class="entry empty">
            <span class="empty-dot" />暂无待处理事项
          </div>
          <div v-for="(n, i) in notifications" :key="i" class="entry notif" @click="n.to && router.push(n.to)">
            <span class="notif-dot" :class="{ warn: n.level === 'warn' }" />
            <span class="notif-text">{{ n.text }}</span>
            <el-icon v-if="n.to" class="entry-go"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
    </div>

    <PlatformBar :platform="platform" :running-count="runningCount" />
  </section>
</template>

<style scoped>
.portal {
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

/* 问候区 */
.hello { margin: 2px 0 18px; }
.hello-title {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 10px;
}
.hello-role {
  font-size: 12px;
  font-weight: 600;
  color: #0b4f9e;
  background: #eaf2fb;
  border-radius: 5px;
  padding: 3px 10px;
}
.hello-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #8a97a8;
}

/* 今日概览四卡 */
.ov-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}
.ov-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.ov-value {
  font-size: 26px;
  font-weight: 800;
  color: #1f2d3d;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.ov-label {
  font-size: 13px;
  color: #4a5a6d;
  font-weight: 600;
  margin-top: 4px;
}
.ov-hint {
  font-size: 11.5px;
  color: #98a2b0;
  margin-top: 2px;
}

/* 双栏面板 */
.two-col {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 14px;
  align-items: start;
}
.panel {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 16px 18px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
}
.go-icon { font-size: 12px; }

/* 快捷入口行 */
.entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 6px;
  border-bottom: 1px solid #f2f4f7;
  cursor: pointer;
  border-radius: 6px;
}
.entry:last-child { border-bottom: 0; }
.entry:hover { background: #f7fafd; }
.entry-main { flex: 1; min-width: 0; }
.entry-name {
  font-size: 14.5px;
  font-weight: 700;
  color: #1f2d3d;
}
.entry-desc {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 2px;
}
.entry-go { color: #c0c8d2; font-size: 13px; }

.app-tile {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 19px;
}
.app-tile.blue { background: linear-gradient(135deg, #e3eefb, #d2e4f7); color: #0b4f9e; }
.app-tile.violet { background: linear-gradient(135deg, #efe8fd, #e2d8fa); color: #6d28d9; }
.app-tile.green { background: linear-gradient(135deg, #e2f6ed, #d0efe0); color: #047857; }

/* 状态点样式（与快捷入口行内） */
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

/* 通知行 */
.notif-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2f7bff;
  flex-shrink: 0;
}
.notif-dot.warn { background: #d97706; }
.notif-text {
  flex: 1;
  font-size: 13px;
  color: #34455c;
  line-height: 1.6;
}
.entry.empty {
  color: #98a2b0;
  font-size: 13px;
  cursor: default;
  justify-content: center;
  padding: 22px 0;
}
.empty-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d5dae1;
}

@media (max-width: 1000px) {
  .ov-grid { grid-template-columns: repeat(2, 1fr); }
  .two-col { grid-template-columns: 1fr; }
}
</style>
