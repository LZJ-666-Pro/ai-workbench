<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  APP_LABELS, CATEGORY_LABELS, loadPlatformLogs,
  type LogPage,
} from '../api/platform'

const route = useRoute()
const router = useRouter()

/** 筛选条件直接由 URL query 驱动：日志页常被拿来贴给别人看，地址必须能还原现场 */
const query = reactive({
  app: (route.query.app as string) || '',
  category: (route.query.category as string) || '',
  result: (route.query.result as string) || '',
  keyword: (route.query.keyword as string) || '',
  page: Number(route.query.page) || 1,
  size: 20,
})

const data = ref<LogPage | null>(null)
const loading = ref(false)
const autoRefresh = ref(false)
let timer: number | undefined

const resultTag: Record<string, string> = { SUCCESS: 'success', DENY: 'danger', FAIL: 'warning' }

async function load() {
  loading.value = true
  try {
    data.value = await loadPlatformLogs(query)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载日志失败')
  } finally {
    loading.value = false
  }
}

/** 筛选变化时回到第一页并同步到地址栏，避免"翻了页再改筛选"出现空结果 */
function applyFilter() {
  query.page = 1
  syncUrl()
  load()
}

function syncUrl() {
  router.replace({
    path: '/logs',
    query: Object.fromEntries(
      Object.entries(query)
        .filter(([k, v]) => k !== 'size' && v !== '' && v !== undefined && !(k === 'page' && v === 1))
        .map(([k, v]) => [k, String(v)]),
    ),
  })
}

function reset() {
  query.app = ''
  query.category = ''
  query.result = ''
  query.keyword = ''
  applyFilter()
}

function onPageChange(page: number) {
  query.page = page
  syncUrl()
  load()
}

function toggleAuto() {
  autoRefresh.value = !autoRefresh.value
  setupTimer()
}

function setupTimer() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (autoRefresh.value) {
    timer = window.setInterval(load, 5000)
  }
}

// 从首页卡片点进来时带 ?app=bank，此处跟随地址变化重新加载（含浏览器前进后退）
watch(() => route.query.app, v => {
  const next = (v as string) || ''
  if (next !== query.app) {
    query.app = next
    query.page = 1
    load()
  }
})

onMounted(load)
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })

const summary = computed(() => data.value?.summary ?? { total: 0, success: 0, deny: 0, fail: 0 })
const appNames = Object.entries(APP_LABELS)
const shortTrace = (t: string | null) => (t ? t.substring(0, 12) : '—')
</script>

<template>
  <section class="logs-page">
    <div class="page-head">
      <div>
        <h1>平台运行日志</h1>
        <p class="sub">
          记录每一次对话、工具调用、登录、护栏拦截与知识检索。TraceId 与后端日志里的
          <span class="mono">[traceId]</span> 一致，用它可拉出一次请求贯穿的全部日志。
        </p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button :type="autoRefresh ? 'primary' : 'default'" @click="toggleAuto">
          {{ autoRefresh ? '自动刷新中' : '自动刷新' }}
        </el-button>
      </div>
    </div>

    <!-- 汇总条：不随分页变化，翻页时数字不动才读得懂 -->
    <div class="summary">
      <div class="sum-item">
        <span class="sum-value">{{ summary.total.toLocaleString() }}</span>
        <span class="sum-label">总事件</span>
      </div>
      <div class="sum-item">
        <span class="sum-value ok">{{ summary.success.toLocaleString() }}</span>
        <span class="sum-label">成功</span>
      </div>
      <div class="sum-item">
        <span class="sum-value deny">{{ summary.deny.toLocaleString() }}</span>
        <span class="sum-label">拒绝 / 拦截</span>
      </div>
      <div class="sum-item">
        <span class="sum-value fail">{{ summary.fail.toLocaleString() }}</span>
        <span class="sum-label">失败</span>
      </div>
    </div>

    <div class="toolbar">
      <el-select v-model="query.app" placeholder="全部应用" clearable style="width: 150px" @change="applyFilter">
        <el-option v-for="[key, meta] in appNames" :key="key" :label="meta.name" :value="key" />
      </el-select>
      <el-select v-model="query.category" placeholder="全部类别" clearable style="width: 140px" @change="applyFilter">
        <el-option v-for="(label, key) in CATEGORY_LABELS" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.result" placeholder="全部结果" clearable style="width: 130px" @change="applyFilter">
        <el-option label="SUCCESS" value="SUCCESS" />
        <el-option label="DENY" value="DENY" />
        <el-option label="FAIL" value="FAIL" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="搜索动作 / 明细 / 会话 / TraceId"
        clearable
        style="width: 280px"
        :prefix-icon="Search"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />
      <el-button type="primary" @click="applyFilter">查询</el-button>
      <el-button link @click="reset">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="data?.list ?? []" stripe class="log-table">
      <el-table-column prop="time" label="时间" width="164">
        <template #default="{ row }"><span class="dim mono">{{ row.time }}</span></template>
      </el-table-column>
      <el-table-column label="应用" width="106">
        <template #default="{ row }">
          <span class="app-dot" :class="APP_LABELS[row.app]?.tone ?? 'grey'" />
          <span class="app-name">{{ APP_LABELS[row.app]?.name ?? row.app }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="92">
        <template #default="{ row }">
          <span class="dim">{{ CATEGORY_LABELS[row.category] ?? row.category }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="action" label="动作" width="170">
        <template #default="{ row }"><span class="mono">{{ row.action }}</span></template>
      </el-table-column>
      <el-table-column prop="detail" label="明细" min-width="260" show-overflow-tooltip />
      <el-table-column label="结果" width="98" align="center">
        <template #default="{ row }">
          <el-tag :type="(resultTag[row.result] || 'info') as any" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="86" align="right">
        <template #default="{ row }">
          <span class="dim mono">{{ row.durationMs == null ? '—' : row.durationMs + 'ms' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="TraceId" width="132">
        <template #default="{ row }">
          <!-- 与后端日志里的 [traceId] 一致：复制它即可在日志中捞出这一次请求的全部行 -->
          <el-tooltip v-if="row.traceId" :content="row.traceId" placement="top">
            <span class="mono dim">{{ shortTrace(row.traceId) }}</span>
          </el-tooltip>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的日志" /></template>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="data?.total ?? 0"
      layout="total, prev, pager, next"
      background
      class="pager"
      @current-change="onPageChange"
    />
  </section>
</template>

<style scoped>
.logs-page {
  --el-color-primary: #0b4f9e;
  padding: 22px 28px 30px;
  max-width: 1440px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.page-head h1 {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 800;
  color: #1f2d3d;
}
.sub {
  margin: 0;
  font-size: 12.5px;
  color: #8a97a8;
  line-height: 1.7;
  max-width: 720px;
}
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

.summary {
  display: flex;
  gap: 34px;
  background: #f0f5fb;
  border: 1px solid #dbe7f3;
  border-radius: 8px;
  padding: 14px 22px;
  margin-bottom: 16px;
}
.sum-item { display: flex; flex-direction: column; }
.sum-value {
  font-size: 21px;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
}
.sum-value.ok { color: #0a8f3c; }
.sum-value.deny { color: #c0392b; }
.sum-value.fail { color: #b26a00; }
.sum-label { font-size: 12px; color: #8a97a8; margin-top: 2px; }

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.log-table { font-size: 12.5px; }
.app-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
.app-dot.blue { background: #0b4f9e; }
.app-dot.violet { background: #6d28d9; }
.app-dot.green { background: #047857; }
.app-dot.grey { background: #98a2b0; }
.app-name { font-size: 12.5px; color: #1f2d3d; }

.mono { font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; }
.dim { color: #98a2b0; font-size: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
