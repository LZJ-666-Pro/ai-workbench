<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  OfficeBuilding, Collection, Microphone, ArrowRight,
  ChatDotRound, Operation, CircleCheck, Monitor, Bell,
} from '@element-plus/icons-vue'
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

/** 今日概览四卡：数字全部来自接口，空态显示 0 / —，不编数字；图标是卡片的视觉锚点 */
const overview = computed(() => [
  { label: '今日总会话', value: platform.value.sessionsToday.toLocaleString(), hint: '全部应用的对话轮次', icon: ChatDotRound, tone: 'blue' },
  { label: '今日工具调用', value: platform.value.eventsToday.toLocaleString(), hint: 'Agent 触发的业务操作', icon: Operation, tone: 'green' },
  { label: '平均成功率', value: `${platform.value.successRate}%`, hint: '按今日平台事件计算', icon: CircleCheck, tone: 'violet' },
  { label: '运行中应用', value: `${runningCount.value} / ${apps.value.length}`, hint: '在线状态实时探测', icon: Monitor, tone: 'cyan' },
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
      <!-- 页头：eyebrow 小字 + 问候；右侧环境绿点与数据时间（对齐原型两行右对齐） -->
      <div class="hello">
        <div class="hello-left">
          <span class="eyebrow">工作台总览</span>
          <h1 class="hello-title">
            {{ greeting }}，{{ auth.user?.displayName ?? '访客' }}
            <span class="hello-role">{{ roleLabel(auth.user?.platformRole) }}</span>
          </h1>
          <p class="hello-sub">{{ today }} · 从下面的入口开始今天的工作。</p>
        </div>
        <div class="hello-right">
          <span class="env-chip"><i class="dot" />生产环境</span>
          <span class="sync-chip">数据更新于 {{ platform.updatedAt }}</span>
        </div>
      </div>

      <!-- 今日概览：图标+标签在上、大数字居中、副注在底（对齐原型纵排卡片） -->
      <div class="ov-grid">
        <div v-for="o in overview" :key="o.label" class="ov-card">
          <div class="ov-top">
            <span class="ov-icon" :class="o.tone"><el-icon><component :is="o.icon" /></el-icon></span>
            <span class="ov-label">{{ o.label }}</span>
          </div>
          <span class="ov-value" :class="{ primary: o.tone === 'blue' }">{{ o.value }}</span>
          <span class="ov-hint">{{ o.hint }}</span>
        </div>
      </div>

      <div class="two-col">
        <!-- 快捷入口 -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">常用应用</span>
              <span class="panel-desc">按角色分配的业务入口，点击直接进入</span>
            </div>
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
            <div class="panel-head-main">
              <span class="panel-title">待办与通知</span>
              <span class="panel-desc">需要你关注的事项</span>
            </div>
          </div>
          <div v-if="!notifications.length" class="empty-state">
            <span class="empty-bell"><el-icon><Bell /></el-icon></span>
            <div class="empty-title">暂无待处理事项</div>
            <div class="empty-sub">新的审批、提醒会出现在这里</div>
            <el-button class="empty-btn" plain size="small" @click="router.push('/logs')">查看全部通知</el-button>
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

/* 页头：eyebrow 分区锚点 + 右侧环境信息，底部不画线（统计卡的分隔已足够） */
.hello {
  margin: 2px 0 20px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}
.eyebrow {
  display: inline-block;
  font-size: 12.5px;
  color: #8a97a8;
  margin-bottom: 8px;
}
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
.hello-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  padding-bottom: 6px;
  flex-shrink: 0;
}
/* 原型：绿点绿字无底框 + 下方灰字数据时间 */
.env-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: #0a8f3c;
}
.env-chip .dot { width: 8px; height: 8px; border-radius: 50%; background: #0a8f3c; }
.sync-chip {
  font-size: 12px;
  color: #98a2b0;
}

/* 今日概览：图标+标签一行、大数字、副注（纵排对齐原型） */
.ov-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.ov-card {
  background: #fff;
  border: 1px solid #e8ecf1;
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 2px 8px rgba(31, 45, 61, 0.04);
}
.ov-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.ov-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 17px;
}
.ov-icon.blue { background: #e3eefb; color: #1f6fd6; }
.ov-icon.green { background: #e2f6ed; color: #047857; }
.ov-icon.violet { background: #efe8fd; color: #6d28d9; }
.ov-icon.cyan { background: #e0f4f8; color: #0e7490; }
.ov-label {
  font-size: 14px;
  font-weight: 700;
  color: #1f2d3d;
}
.ov-value {
  font-size: 28px;
  font-weight: 800;
  color: #1f2d3d;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.ov-value.primary { color: #1f6fd6; }
.ov-hint {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 6px;
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
  border: 1px solid #e8ecf1;
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: 0 2px 8px rgba(31, 45, 61, 0.04);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f2f4f7;
}
.panel-head-main { display: flex; flex-direction: column; min-width: 0; }
.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
}
.panel-desc {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 2px;
}
.go-icon { font-size: 12px; }

/* 快捷入口行：灰底圆角条（对齐原型，行间留 gap，不再是分隔线列表） */
.entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  background: #f7f8fa;
  border: 1px solid transparent;
  cursor: pointer;
  border-radius: 10px;
  transition: background 0.15s, border-color 0.15s;
}
.entry:last-child { margin-bottom: 0; }
.entry:hover { background: #eef4fb; border-color: #d8e6f5; }
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
.app-tile.blue { background: #e3eefb; color: #0b4f9e; }
.app-tile.violet { background: #efe8fd; color: #6d28d9; }
.app-tile.green { background: #e2f6ed; color: #047857; }

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
/* 空态：大紫色圆底铃铛 + 主副文案 + 操作按钮（对齐原型） */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 30px 0 26px;
}
.empty-bell {
  width: 84px;
  height: 84px;
  border-radius: 50%;
  background: #efe8fd;
  color: #8b5cf6;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  margin-bottom: 16px;
}
.empty-title {
  font-size: 14.5px;
  font-weight: 700;
  color: #1f2d3d;
}
.empty-sub {
  font-size: 12.5px;
  color: #98a2b0;
  margin-top: 4px;
}
.empty-btn {
  margin-top: 14px;
  color: #0b4f9e;
  border-color: #9fc0e4;
}
.empty-btn:hover {
  background: #eaf2fb;
  border-color: #0b4f9e;
  color: #0b4f9e;
}

@media (max-width: 1000px) {
  .ov-grid { grid-template-columns: repeat(2, 1fr); }
  .two-col { grid-template-columns: 1fr; }
}
</style>
