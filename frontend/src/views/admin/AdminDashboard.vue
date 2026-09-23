<template>
  <div v-loading="loading">
    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">总余额（元）</div>
        <div class="stat-value">¥ {{ fmt(stats?.totalBalance) }}</div>
        <div class="stat-sub" :class="netFlowClass()">
          今日净流入 {{ stats && stats.todayTxnAmount >= 0 ? '+' : '' }}¥ {{ fmt(stats?.todayTxnAmount) }}
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-label">账户总数</div>
        <div class="stat-value">{{ stats?.accountCount ?? '-' }}</div>
        <div class="stat-sub">对公 {{ stats?.corporateCount ?? '-' }} · 个人 {{ stats?.personalCount ?? '-' }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">客户数</div>
        <div class="stat-value">{{ stats?.customerCount ?? '-' }}</div>
        <div class="stat-sub">对公企业客户为主体</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日流水</div>
        <div class="stat-value">{{ stats?.todayTxnCount ?? '-' }} 笔</div>
        <div class="stat-sub" :class="dayDiffClass()">{{ dayDiffText() }}</div>
      </div>
    </div>
    <div class="updated-at">数据更新时间：{{ updatedAt }}</div>

    <!-- 图表 -->
    <div class="chart-row">
      <div class="chart-card">
        <div class="card-title">账户余额分布</div>
        <BaseChart :option="pieOption" height="320px" />
      </div>
      <div class="chart-card">
        <div class="card-title-row">
          <span class="card-title">每日收支趋势</span>
          <el-radio-group :model-value="flowDays" size="small" @update:model-value="switchFlow($event as 7 | 30)">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
        </div>
        <BaseChart :option="lineOption" height="320px" />
      </div>
    </div>

    <!-- 最近交易 + 待办 -->
    <div class="bottom-row">
      <div class="chart-card">
        <div class="card-title-row">
          <span class="card-title">最近交易流水</span>
          <el-button size="small" link type="primary" @click="$router.push('/admin/funds')">进入资金管理 →</el-button>
        </div>
        <el-table :data="recentTxns" size="small" stripe style="margin-top: 10px">
          <el-table-column prop="createdAt" label="时间" width="165" />
          <el-table-column prop="accountNo" label="账号" width="100">
            <template #default="{ row }"><span class="mono">{{ row.accountNo }}</span></template>
          </el-table-column>
          <el-table-column prop="owner" label="户名" min-width="110" />
          <el-table-column prop="description" label="摘要" min-width="190" show-overflow-tooltip />
          <el-table-column label="金额（元）" align="right" width="130">
            <template #default="{ row }">
              <span :class="row.amount >= 0 ? 'in' : 'out'">{{ row.amount >= 0 ? '+' : '' }}{{ fmt(row.amount) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="chart-card todo-card">
        <div class="card-title">待办事项</div>
        <div class="todo-list">
          <div class="todo-item" @click="$router.push('/admin/approvals')">
            <el-icon class="todo-icon warn"><DocumentChecked /></el-icon>
            <div>
              <div class="todo-title">待审批转账订单 <b>{{ board?.pending ?? 0 }}</b> 笔</div>
              <div class="todo-sub">AI 发起的转账等待人工授权</div>
            </div>
          </div>
          <div class="todo-item" @click="$router.push('/admin/audit')">
            <el-icon class="todo-icon bad"><Warning /></el-icon>
            <div>
              <div class="todo-title">风控拦截记录 <b>{{ denyCount }}</b> 条</div>
              <div class="todo-sub">审计日志中的 DENY 结果，建议复核</div>
            </div>
          </div>
          <div class="todo-item" @click="$router.push('/admin/visit')">
            <el-icon class="todo-icon ok"><Calendar /></el-icon>
            <div>
              <div class="todo-title">客户走访 <b>3</b> 家待回访</div>
              <div class="todo-sub">客户经理平台 · 本周内安排</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, shallowRef, ref } from 'vue'
import { DocumentChecked, Warning, Calendar } from '@element-plus/icons-vue'
import { adminApi } from '../../api/admin'
import type { ApprovalBoard, DailyFlowPoint, OverviewStats, TxnView } from '../../api/admin'
import BaseChart from '../../components/admin/BaseChart.vue'
import type { EChartsOption } from 'echarts'

const stats = ref<OverviewStats | null>(null)
const recentTxns = ref<TxnView[]>([])
const board = ref<ApprovalBoard | null>(null)
const denyCount = ref(0)
const updatedAt = ref('')
// EChartsOption 是深层递归类型，必须 shallowRef 避免 TS 深度展开导致类型爆炸
const pieOption = shallowRef<EChartsOption>({})
const lineOption = shallowRef<EChartsOption>({})
const flowDays = ref<7 | 30>(7)
const loading = ref(false)

/** 环形图用同一蓝色系深浅变化，仅最高余额客户用绿色强调 */
const BLUE_SCALE = ['#0b4f9e', '#2f6cb3', '#5288c7', '#79a5d4', '#9fbfe0', '#c3d7ec', '#dde8f4', '#8a97a8']
const GREEN_ACCENT = '#0a8f3c'

async function loadFlow() {
  const rows: DailyFlowPoint[] = await adminApi.dailyFlow(flowDays.value)
  lineOption.value = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['入账', '支出'] },
    grid: { left: 70, right: 24, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: rows.map(r => r.date.slice(5)) },
    yAxis: { type: 'value', name: '元' },
    series: [
      { name: '入账', type: 'line', smooth: true, data: rows.map(r => r.income), itemStyle: { color: '#0b8f3c' }, areaStyle: { opacity: 0.08 } },
      { name: '支出', type: 'line', smooth: true, data: rows.map(r => r.expense), itemStyle: { color: '#d93026' }, areaStyle: { opacity: 0.08 } },
    ],
  }
}

async function switchFlow(d: 7 | 30) {
  flowDays.value = d
  await loadFlow()
}

onMounted(async () => {
  loading.value = true
  updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
  try {
    const [overview, dist, txns, approvals, denyPage] = await Promise.all([
      adminApi.overview(),
      adminApi.balanceDistribution(),
      adminApi.transactions('', 'all', 1, 8),
      adminApi.approvals(),
      adminApi.auditLogs('DENY', '', 1, 1),
    ])
    stats.value = overview
    recentTxns.value = txns.list
    board.value = approvals
    denyCount.value = denyPage.total

    let maxIdx = 0
    dist.forEach((d, i) => { if (d.balance > dist[maxIdx].balance) maxIdx = i })
    pieOption.value = {
      tooltip: { trigger: 'item', formatter: '{b}<br/>余额：{c} 元（{d}%）' },
      legend: { orient: 'vertical', right: 8, top: 'middle' },
      series: [{
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['40%', '50%'],
        data: dist.map((d, i) => ({
          name: `${d.owner}（${d.accountNo}）`,
          value: d.balance,
          itemStyle: { color: i === maxIdx ? GREEN_ACCENT : BLUE_SCALE[i % BLUE_SCALE.length] },
        })),
        label: { show: false },
      }],
    }
    await loadFlow()
  } finally {
    loading.value = false
  }
})

const fmt = (n?: number) => (n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const netFlowClass = () => ((stats.value?.todayTxnAmount ?? 0) < 0 ? 'sub-red' : 'sub-green')
const dayDiffText = () => {
  if (!stats.value) return '–'
  const d = stats.value.todayTxnCount - stats.value.yesterdayTxnCount
  return d === 0 ? '与昨日持平' : `较昨日 ${d > 0 ? '+' : ''}${d} 笔`
}
const dayDiffClass = () =>
  ((stats.value?.todayTxnCount ?? 0) - (stats.value?.yesterdayTxnCount ?? 0)) >= 0 ? 'sub-green' : 'sub-red'
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.stat-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 4px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06);
}

.stat-label {
  font-size: 12.5px;
  color: #7a8798;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #0b4f9e;
  font-variant-numeric: tabular-nums;
}

.stat-sub {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 6px;
}

.sub-green { color: #0a8f3c; }
.sub-red { color: #d93026; }

.updated-at {
  font-size: 12px;
  color: #98a2b0;
  text-align: right;
  margin: 8px 2px 14px;
}

.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.bottom-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 14px;
}

.chart-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 4px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06);
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.in { color: #0a8f3c; font-weight: 600; }
.out { color: #d93026; font-weight: 600; }

.todo-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.todo-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid #edf0f4;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.todo-item:hover { background: #f5f9ff; }

.todo-icon { margin-top: 2px; }
.todo-icon.warn { color: #b26a00; }
.todo-icon.bad { color: #d93026; }
.todo-icon.ok { color: #0a8f3c; }

.todo-title { font-size: 13px; color: #1f2d3d; }
.todo-title b { color: #0b4f9e; }
.todo-sub { font-size: 12px; color: #98a2b0; margin-top: 2px; }
</style>
