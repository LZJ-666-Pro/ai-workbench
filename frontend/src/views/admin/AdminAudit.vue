<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import type { AuditLogView } from '../../api/admin'

const query = reactive({ result: '', toolName: '', page: 1, size: 10 })
const rows = ref<AuditLogView[]>([])
const total = ref(0)
const loading = ref(false)

const resultTag: Record<string, string> = {
  SUCCESS: 'success',
  ALLOW: 'primary',
  DENY: 'danger',
  FAIL: 'warning',
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.auditLogs(query.result, query.toolName, query.page, query.size)
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.page = 1
  load()
}

onMounted(load)

const shortId = (memoryId: string) => memoryId.split(':').pop()?.substring(0, 8) ?? memoryId
</script>

<template>
  <div class="page-card">
    <div class="hint">
      记录 AI 助手每次工具调用的审计轨迹：谁（会话）、何时、调了什么工具、参数与结果（成功 / 放行 / 风控拒绝 / 失败）。
    </div>
    <div class="toolbar">
      <span class="toolbar-label">调用结果：</span>
      <el-radio-group v-model="query.result" @change="reset">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="SUCCESS">SUCCESS</el-radio-button>
        <el-radio-button value="ALLOW">ALLOW</el-radio-button>
        <el-radio-button value="DENY">DENY（风控拒绝）</el-radio-button>
        <el-radio-button value="FAIL">FAIL（调用失败）</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="query.toolName"
        placeholder="按工具名筛选，如 transfer"
        clearable
        style="width: 200px"
        @change="reset"
      />
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column label="会话" width="110">
        <template #default="{ row }">
          <el-tooltip :content="row.memoryId" placement="top">
            <span class="mono">{{ shortId(row.memoryId) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="toolName" label="工具" width="120">
        <template #default="{ row }"><span class="mono">{{ row.toolName }}</span></template>
      </el-table-column>
      <el-table-column prop="detail" label="调用明细" min-width="260" show-overflow-tooltip />
      <el-table-column label="结果" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="(resultTag[row.result] || 'info') as any" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="170">
        <template #default="{ row }"><span class="dim">{{ row.createdAt }}</span></template>
      </el-table-column>
      <template #empty><el-empty description="暂无审计记录" /></template>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      background
      class="pager"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.hint {
  font-size: 12.5px;
  color: #98a2b0;
  margin-bottom: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.toolbar-label {
  font-size: 13px;
  color: #7a8798;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.dim {
  color: #98a2b0;
  font-size: 12px;
}
</style>
