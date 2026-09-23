<script setup lang="ts">
import { onMounted, shallowRef, ref } from 'vue'
import { adminApi } from '../../api/admin'
import type { BalancePoint, DailyFlowPoint, OverviewStats } from '../../api/admin'
import BaseChart from '../../components/admin/BaseChart.vue'
import type { EChartsOption } from 'echarts'

const stats = ref<OverviewStats | null>(null)
// EChartsOption 是深层递归类型，必须 shallowRef 避免 TS 深度展开导致类型爆炸
const pieOption = shallowRef<EChartsOption>({})
const lineOption = shallowRef<EChartsOption>({})
const flowDays = ref<7 | 30>(7)
const loading = ref(false)

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
  try {
    stats.value = await adminApi.overview()
    const dist: BalancePoint[] = await adminApi.balanceDistribution()
    pieOption.value = {
      tooltip: { trigger: 'item', formatter: '{b}<br/>余额：{c} 元（{d}%）' },
      legend: { orient: 'vertical', right: 8, top: 'middle' },
      series: [{
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['40%', '50%'],
        data: dist.map(d => ({ name: `${d.owner}（${d.accountNo}）`, value: d.balance })),
        label: { show: false },
      }],
    }
    await loadFlow()
  } finally {
    loading.value = false
  }
})

const fmt = (n?: number) => (n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
</script>

<template>
  <div v-loading="loading">
    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">总余额（元）</div>
        <div class="stat-value">¥ {{ fmt(stats?.totalBalance) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">账户总数</div>
        <div class="stat-value">{{ stats?.accountCount ?? '-' }}</div>
        <div class="stat-sub">个人 {{ stats?.personalCount ?? '-' }} / 对公 {{ stats?.corporateCount ?? '-' }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">客户数</div>
        <div class="stat-value">{{ stats?.customerCount ?? '-' }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日流水</div>
        <div class="stat-value">{{ stats?.todayTxnCount ?? '-' }} 笔</div>
        <div class="stat-sub">¥ {{ fmt(stats?.todayTxnAmount) }}</div>
      </div>
    </div>

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
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.stat-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
  padding: 16px 18px;
  box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06);
}

.stat-label {
  font-size: 12.5px;
  color: #7a8798;
  margin-bottom: 8px;
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

.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.chart-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
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
</style>
