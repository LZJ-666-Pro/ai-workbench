<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Connection, Cpu, DataLine, MagicStick, Odometer, Refresh, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import BaseChart from '../components/admin/BaseChart.vue'
import type { EChartsOption } from 'echarts'
import {
  APP_LABELS, loadPlatformLogs, loadPlatformMonitor, probeAppRunning, probePlatformRunning,
  type LogPage, type MonitorCheck, type MonitorPayload,
} from '../api/platform'

const router = useRouter()

const loading = ref(false)
const error = ref('')
const data = ref<MonitorPayload | null>(null)
const checkedAt = ref('')
const autoRefresh = ref(true)
let timer: number | null = null

/** 应用服务在线状态：与后端探测彼此独立——后端探测 8080 自己（数据库/向量库/LLM），
 *  各 Agent 服务是否在线只有真的问一次才知道（见 probeAppRunning 注释） */
interface ServiceRow { key: string; name: string; desc: string; basePath: string }
const SERVICES: ServiceRow[] = [
  { key: 'platform', name: '平台数据服务', desc: '8080 · 登录/工作台/监控/知识库/数据源', basePath: '/platform' },
  { key: 'bank', name: '银行助手服务', desc: '8081 · 银行业务 Agent', basePath: '/bank' },
  { key: 'knowledge', name: '个人知识库服务', desc: '8082 · 知识库 Agent 与个人库', basePath: '/knowledge' },
  { key: 'interview', name: '面试模拟器服务', desc: '8083 · 面试模拟 Agent', basePath: '/interview' },
]
const serviceStatus = ref<Record<string, 'UP' | 'DOWN'>>({})

const recentLogs = ref<LogPage | null>(null)

const statusMeta: Record<MonitorCheck['status'], { label: string; cls: string }> = {
  UP: { label: '正常', cls: 'up' },
  DOWN: { label: '异常', cls: 'down' },
  DISABLED: { label: '未启用', cls: 'disabled' },
  UNCONFIGURED: { label: '未配置', cls: 'unconfigured' },
}

const checkIcon: Record<string, typeof Connection> = {
  mysql: DataLine,
  vector: Connection,
  llm: MagicStick,
}

const overallOk = computed(() =>
  (data.value?.checks ?? []).every(c => c.status === 'UP' || c.status === 'DISABLED')
  && Object.values(serviceStatus.value).every(s => s === 'UP'))

const trendOption = computed<EChartsOption>(() => ({
  grid: { left: 40, right: 16, top: 24, bottom: 28 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: data.value?.trend24h.map(p => p.hour) ?? [],
    axisLine: { lineStyle: { color: '#dfe3e9' } },
    axisLabel: { color: '#8a97a8', fontSize: 11 },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    axisLabel: { color: '#8a97a8', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f2f4f7' } },
  },
  series: [{
    type: 'bar',
    data: data.value?.trend24h.map(p => p.count) ?? [],
    barMaxWidth: 18,
    itemStyle: { color: '#1f6fd6', borderRadius: [3, 3, 0, 0] },
  }],
}))

function fmtUptime(ms: number): string {
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function fmtTime(iso: string): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await loadPlatformMonitor()
    checkedAt.value = new Date().toISOString()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
  } finally {
    loading.value = false
  }
}

/** 各服务逐个真实请求一次：Agent 应用问 Agent 列表，平台数据服务问免认证 ping */
async function probeServices() {
  await Promise.all(SERVICES.map(async s => {
    serviceStatus.value[s.key] = (s.key === 'platform'
      ? await probePlatformRunning()
      : await probeAppRunning(s.basePath, s.key)) ? 'UP' : 'DOWN'
  }))
}

async function loadRecent() {
  try {
    recentLogs.value = await loadPlatformLogs({ size: 10 })
  } catch { /* 监控主体不受最近事件加载失败影响 */ }
}

async function refreshAll() {
  await Promise.all([load(), probeServices(), loadRecent()])
}

onMounted(() => {
  void refreshAll()
  timer = window.setInterval(() => {
    if (autoRefresh.value) void refreshAll()
  }, 30_000)
})

onBeforeUnmount(() => { if (timer !== null) window.clearInterval(timer) })

function manualRefresh() {
  void refreshAll()
  ElMessage.success('检测完成')
}
</script>

<template>
  <section class="monitor-page">
    <div class="page-inner">
      <!-- 页头：面包屑 + 大标题 + 右侧检测控制（与设置页/工作台同一套页头规范） -->
      <div class="section-head">
        <div class="head-row">
          <div>
            <div class="crumb"><span>平台运维</span><el-icon class="crumb-sep"><ArrowRight /></el-icon></div>
            <h2 class="section-title">平台监控</h2>
            <p class="section-sub">基础设施、应用服务与运行时的实时健康状态，每 30 秒自动刷新。</p>
          </div>
          <div class="head-actions">
            <span class="sync-chip">检测于 {{ fmtTime(checkedAt) }}</span>
            <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" />
            <el-button type="primary" plain size="small" :loading="loading" @click="manualRefresh">
              <el-icon v-if="!loading"><Refresh /></el-icon>&nbsp;立即检测
            </el-button>
          </div>
        </div>
        <div v-if="overallOk" class="overall ok"><i class="dot" />全部系统运行正常</div>
        <div v-else class="overall bad"><el-icon><Warning /></el-icon>部分组件存在异常，请查看下方明细</div>
      </div>

      <div v-if="error" class="error-banner">
        <el-icon><Warning /></el-icon>
        {{ error }}（平台监控接口需要 ADMIN 登录，且 8080 平台数据服务在线）
      </div>

      <div class="grid-3">
        <!-- 基础设施健康：后端真实探测（SELECT 1 / TCP 连通 / 配置在位） -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">基础设施健康</span>
              <span class="panel-desc">数据库 · 向量库 · 模型服务</span>
            </div>
          </div>
          <div v-for="c in data?.checks ?? []" :key="c.key" class="check-row">
            <span class="check-icon"><el-icon><component :is="checkIcon[c.key] ?? Odometer" /></el-icon></span>
            <div class="check-main">
              <div class="check-name">{{ c.name }}</div>
              <div class="check-detail">{{ c.detail }}</div>
            </div>
            <div class="check-right">
              <span class="status" :class="statusMeta[c.status].cls">
                <i class="dot" />{{ statusMeta[c.status].label }}
              </span>
              <span v-if="c.latencyMs !== null" class="latency">{{ c.latencyMs }} ms</span>
            </div>
          </div>
          <div v-if="!data" class="panel-empty">检测中…</div>
        </div>

        <!-- 应用服务：前端逐服务探测 Agent 列表接口 -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">应用服务</span>
              <span class="panel-desc">各 Agent 后端是否在线</span>
            </div>
          </div>
          <div v-for="s in SERVICES" :key="s.key" class="check-row">
            <span class="check-icon"><el-icon><Cpu /></el-icon></span>
            <div class="check-main">
              <div class="check-name">{{ s.name }}</div>
              <div class="check-detail">{{ s.desc }}</div>
            </div>
            <div class="check-right">
              <span v-if="serviceStatus[s.key]" class="status" :class="serviceStatus[s.key] === 'UP' ? 'up' : 'down'">
                <i class="dot" />{{ serviceStatus[s.key] === 'UP' ? '运行中' : '未启动' }}
              </span>
              <span v-else class="status disabled"><i class="dot" />检测中</span>
            </div>
          </div>
        </div>

        <!-- JVM 运行时：ManagementFactory 真实指标 -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">运行时状态</span>
              <span class="panel-desc">平台数据服务 JVM（8080）</span>
            </div>
          </div>
          <template v-if="data">
            <div class="kv"><span class="k">已运行</span><span class="v">{{ fmtUptime(data.runtime.uptimeMs) }}</span></div>
            <div class="kv">
              <span class="k">堆内存</span>
              <span class="v">{{ data.runtime.heapUsedMb }} / {{ data.runtime.heapMaxMb }} MB</span>
            </div>
            <div class="heap-bar">
              <div
                class="heap-fill"
                :style="{ width: Math.min(100, data.runtime.heapUsedMb / Math.max(1, data.runtime.heapMaxMb) * 100) + '%' }"
              />
            </div>
            <div class="kv"><span class="k">线程数</span><span class="v">{{ data.runtime.threads }}</span></div>
            <div class="kv"><span class="k">处理器</span><span class="v">{{ data.runtime.processors }} 核</span></div>
            <div class="kv"><span class="k">Java 版本</span><span class="v">{{ data.runtime.javaVersion }}</span></div>
          </template>
          <div v-else class="panel-empty">检测中…</div>
        </div>
      </div>

      <div class="grid-2-1">
        <!-- 近 24 小时事件趋势（platform_event_log 真实聚合） -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">事件趋势 · 近 24 小时</span>
              <span class="panel-desc">平台事件（对话/工具/检索/登录/护栏）按小时分布</span>
            </div>
          </div>
          <BaseChart :option="trendOption" height="240px" />
        </div>

        <!-- 今日事件统计 -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">今日事件</span>
              <span class="panel-desc">自今日零点起</span>
            </div>
          </div>
          <template v-if="data">
            <div class="stat-row">
              <span class="stat-num">{{ data.todayStats.events }}</span>
              <span class="stat-label">事件总数</span>
            </div>
            <div class="stat-row">
              <span class="stat-num deny">{{ data.todayStats.denies }}</span>
              <span class="stat-label">护栏拦截（DENY）</span>
            </div>
            <div class="stat-row">
              <span class="stat-num fail">{{ data.todayStats.fails }}</span>
              <span class="stat-label">执行失败（FAIL）</span>
            </div>
          </template>
          <div v-else class="panel-empty">检测中…</div>
        </div>
      </div>

      <!-- 最近平台事件：复用平台日志接口 -->
      <div class="panel">
        <div class="panel-head">
          <div class="panel-head-main">
            <span class="panel-title">最近平台事件</span>
            <span class="panel-desc">最新 10 条运行日志</span>
          </div>
          <el-button link type="primary" size="small" @click="router.push('/logs')">查看全部</el-button>
        </div>
        <el-table v-if="recentLogs" :data="recentLogs.list" size="small" class="log-table">
          <el-table-column label="时间" width="110">
            <template #default="{ row }">{{ fmtTime(row.time) }}</template>
          </el-table-column>
          <el-table-column label="应用" width="100">
            <template #default="{ row }">{{ APP_LABELS[row.app]?.name ?? row.app }}</template>
          </el-table-column>
          <el-table-column prop="category" label="类别" width="90" />
          <el-table-column prop="action" label="动作" min-width="140" show-overflow-tooltip />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <span class="result" :class="row.result.toLowerCase()">{{ row.result }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="80">
            <template #default="{ row }">{{ row.durationMs !== null ? row.durationMs + ' ms' : '—' }}</template>
          </el-table-column>
          <el-table-column prop="actor" label="操作者" width="100">
            <template #default="{ row }">{{ row.actor ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="panel-empty">加载中…</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.monitor-page {
  background: #f5f7fa;
  min-height: calc(100vh - 56px);
}
.page-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 22px 24px 30px;
}
/* 页头：与设置页同一套面包屑规范 */
.section-head { margin-bottom: 20px; }
.head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #8a97a8;
  margin-bottom: 10px;
}
.crumb-sep { font-size: 12px; color: #c0c8d2; }
.section-title {
  margin: 0;
  font-size: 30px;
  font-weight: 800;
  color: #1f2d3d;
  letter-spacing: 1px;
}
.section-sub {
  margin: 8px 0 0;
  font-size: 13.5px;
  color: #8a97a8;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: 8px;
  flex-shrink: 0;
}
.sync-chip { font-size: 12px; color: #98a2b0; }

/* 总体状态横幅：绿=全部正常 / 红=有异常 */
.overall {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}
.overall.ok { color: #0a8f3c; background: #f0faf4; border: 1px solid #b7e2c6; }
.overall.ok .dot { width: 8px; height: 8px; border-radius: 50%; background: #0a8f3c; }
.overall.bad { color: #c2372f; background: #fdf0ee; border: 1px solid #efc4bd; }
.overall .el-icon { font-size: 15px; }

.error-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px 14px;
  background: #fdf0ee;
  border: 1px solid #efc4bd;
  border-radius: 10px;
  font-size: 13px;
  color: #c2372f;
}

/* 三卡网格 */
.grid-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 18px;
}
.grid-2-1 {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 18px;
  margin-bottom: 18px;
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
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f2f4f7;
}
.panel-head-main { display: flex; flex-direction: column; min-width: 0; }
.panel-title { font-size: 16px; font-weight: 700; color: #1f2d3d; }
.panel-desc { font-size: 12.5px; color: #98a2b0; margin-top: 3px; }
.panel-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 12.5px;
  color: #b6bcc6;
}

/* 健康检查行：图标锚点 + 名称/详情 + 右侧状态与延迟 */
.check-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid #f6f8fa;
}
.check-row:last-of-type { border-bottom: 0; padding-bottom: 4px; }
.check-icon {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: #e3eefb;
  color: #1f6fd6;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 17px;
}
.check-main { min-width: 0; flex: 1; }
.check-name { font-size: 14px; font-weight: 700; color: #1f2d3d; }
.check-detail {
  font-size: 12px;
  color: #98a2b0;
  line-height: 1.5;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.check-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  flex-shrink: 0;
}
.latency { font-size: 11.5px; color: #b6bcc6; font-variant-numeric: tabular-nums; }

/* 状态徽标：绿/红/灰/琥珀四态 */
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 5px;
  padding: 3px 9px;
}
.status .dot { width: 6px; height: 6px; border-radius: 50%; }
.status.up { color: #0a8f3c; background: #f0faf4; border: 1px solid #b7e2c6; }
.status.up .dot { background: #0a8f3c; }
.status.down { color: #c2372f; background: #fdf0ee; border: 1px solid #efc4bd; }
.status.down .dot { background: #c2372f; }
.status.disabled { color: #8a97a8; background: #f5f6f8; border: 1px solid #dfe3e9; }
.status.disabled .dot { background: #b6bcc6; }
.status.unconfigured { color: #b45309; background: #fdf6ec; border: 1px solid #ecd9b0; }
.status.unconfigured .dot { background: #d97706; }

.kv {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid #f6f8fa;
  font-size: 13.5px;
}
.kv:last-of-type { border-bottom: 0; }
.k { width: 72px; flex-shrink: 0; color: #98a2b0; }
.v { color: #1f2d3d; font-weight: 600; font-variant-numeric: tabular-nums; }

.heap-bar {
  height: 7px;
  background: #eef1f5;
  border-radius: 4px;
  overflow: hidden;
  margin: 4px 0 6px;
}
.heap-fill {
  height: 100%;
  background: #1f6fd6;
  border-radius: 4px;
  transition: width 0.4s;
}

/* 今日事件：大数字纵排 */
.stat-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f6f8fa;
}
.stat-row:last-of-type { border-bottom: 0; }
.stat-num {
  font-size: 28px;
  font-weight: 800;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
  min-width: 64px;
}
.stat-num.deny { color: #b45309; }
.stat-num.fail { color: #c2372f; }
.stat-label { font-size: 13px; color: #8a97a8; }

/* 结果徽标 */
.result { font-size: 12px; font-weight: 600; }
.result.success { color: #0a8f3c; }
.result.deny { color: #b45309; }
.result.fail { color: #c2372f; }

.log-table { width: 100%; }

@media (max-width: 1000px) {
  .grid-3, .grid-2-1 { grid-template-columns: 1fr; }
}
</style>
