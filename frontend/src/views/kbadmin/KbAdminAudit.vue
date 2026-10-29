<template>
  <div class="page-card">
    <div class="hint">
      记录知识库应用内每次检索行为：谁在何时查了什么、命中结果如何，用于追查误召回与权限问题。
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column prop="createdAt" label="时间" width="170">
        <template #default="{ row }">
          <span class="dim">{{ row.createdAt }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="用户" width="120" />
      <el-table-column prop="action" label="动作" width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.action }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="明细" min-width="260" show-overflow-tooltip />
      <el-table-column label="结果" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="(resultTag[row.result] || 'info') as any" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无检索记录" /></template>
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

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { kbAdminApi } from '../../api/kbAdmin'
import type { KbAuditRow } from '../../api/kbAdmin'

const query = reactive({ page: 1, size: 10 })
const rows = ref<KbAuditRow[]>([])
const total = ref(0)
const loading = ref(false)

// 结果 tag 配色与银行后台审计页（AdminAudit）一致，未知结果落 info 兜底
const resultTag: Record<string, string> = {
  SUCCESS: 'success',
  ALLOW: 'primary',
  DENY: 'danger',
  FAIL: 'warning',
}

async function load() {
  loading.value = true
  try {
    const r = await kbAdminApi.audit(query.page, query.size)
    rows.value = r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hint {
  font-size: 12.5px;
  color: #98a2b0;
  margin-bottom: 14px;
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
