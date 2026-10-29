<template>
  <div v-loading="loading">
    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">个人库数</div>
        <div class="stat-value">{{ stats?.personalSpaces ?? '-' }}</div>
        <div class="stat-sub">已就绪 {{ stats?.readySpaces ?? '-' }} 个</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">文档总数</div>
        <div class="stat-value">{{ stats?.totalDocs ?? '-' }}</div>
        <div class="stat-sub">累计 {{ fmtInt(stats?.totalWords) }} 字</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">切片总数</div>
        <div class="stat-value">{{ stats?.totalChunks ?? '-' }}</div>
        <div class="stat-sub">检索的最小检索单元</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">总存储</div>
        <div class="stat-value">{{ fmtMb(stats?.totalSizeMb) }} MB</div>
        <div class="stat-sub">全部个人库文档体积</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">解析失败</div>
        <div class="stat-value" :class="{ bad: (stats?.failedDocs ?? 0) > 0 }">{{ stats?.failedDocs ?? '-' }}</div>
        <div class="stat-sub">需在用户库治理中处理</div>
      </div>
    </div>
    <div class="updated-at">数据更新时间：{{ updatedAt }}</div>

    <!-- 近 7 日检索趋势 -->
    <div class="chart-card">
      <div class="card-title">近 7 日检索次数</div>
      <BaseChart :option="lineOption" height="320px" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, shallowRef, ref } from 'vue'
import { kbAdminApi } from '../../api/kbAdmin'
import type { KbOverview } from '../../api/kbAdmin'
import BaseChart from '../../components/admin/BaseChart.vue'
import type { EChartsOption } from 'echarts'

const stats = ref<KbOverview | null>(null)
const updatedAt = ref('')
// EChartsOption 是深层递归类型，必须 shallowRef 避免 TS 深度展开导致类型爆炸
const lineOption = shallowRef<EChartsOption>({})
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
  try {
    const overview = await kbAdminApi.overview()
    stats.value = overview
    lineOption.value = {
      tooltip: { trigger: 'axis' },
      grid: { left: 56, right: 24, top: 32, bottom: 32 },
      xAxis: { type: 'category', data: overview.trend.map(p => p.date) },
      yAxis: { type: 'value', name: '次', minInterval: 1 },
      series: [{
        name: '检索次数',
        type: 'line',
        smooth: true,
        data: overview.trend.map(p => p.count),
        itemStyle: { color: '#0b4f9e' },
        areaStyle: { opacity: 0.08 },
      }],
    }
  } finally {
    loading.value = false
  }
})

const fmtInt = (n?: number) => (n ?? 0).toLocaleString('zh-CN')
/** 存储保留 1 位小数即可：MB 级别下 0.1MB 已是可感知差异 */
const fmtMb = (n?: number) => (n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
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

.stat-value.bad {
  color: #d93026;
}

.stat-sub {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 6px;
}

.updated-at {
  font-size: 12px;
  color: #98a2b0;
  text-align: right;
  margin: 8px 2px 14px;
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
  margin-bottom: 6px;
}
</style>
