<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="按库名或所有者搜索"
        clearable
        style="width: 240px"
      />
      <span class="hint">个人空间按登录用户隔离，平台空间不出现在此列表。</span>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="displayName" label="库名" min-width="150">
        <template #default="{ row }">
          <span class="strong">{{ row.displayName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="空间名" min-width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="所有者" width="110" />
      <el-table-column prop="docCount" label="文档数" width="90" align="center" />
      <el-table-column prop="chunkTotal" label="切片数" width="90" align="center" />
      <el-table-column label="存储" width="110" align="right">
        <template #default="{ row }">
          <span class="mono">{{ fmtKb(row.sizeKb) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170">
        <template #default="{ row }">
          <span class="dim">{{ row.createdAt }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <!-- 甲方要求：文档下钻用独立页面而非右侧抽屉，URL 可直达/刷新/分享 -->
          <el-button size="small" type="primary" plain @click="openDocs(row)">查看文档</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的库" /></template>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      background
      class="pager"
      @current-change="load"
      @size-change="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { kbAdminApi } from '../../api/kbAdmin'
import type { KbLibraryRow } from '../../api/kbAdmin'

// 页大小偏好存 localStorage：用户调过一次，刷新后不该回到默认
const PAGE_SIZE_KEY = 'aiwb-kb-admin-page-size'
const PAGE_SIZES = [10, 20, 50]
function loadPageSize(): number {
  const v = Number(localStorage.getItem(PAGE_SIZE_KEY))
  return PAGE_SIZES.includes(v) ? v : 10
}

const query = reactive({ keyword: '', page: 1, size: loadPageSize() })
const rows = ref<KbLibraryRow[]>([])
const total = ref(0)
const loading = ref(false)

const router = useRouter()

/** 治理动作（启停/删除）在文档页内完成；返回列表时 onMounted 重查，计数自然对齐 */
function openDocs(row: KbLibraryRow) {
  router.push({
    path: `/kb-admin/libraries/${row.spaceId}/docs`,
    query: { name: row.displayName, owner: row.owner },
  })
}

async function load() {
  loading.value = true
  try {
    const r = await kbAdminApi.libraries(query.page, query.size, query.keyword)
    rows.value = r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}

/** 页大小变化：写回偏好并回到第一页重查 */
function onSizeChange() {
  localStorage.setItem(PAGE_SIZE_KEY, String(query.size))
  query.page = 1
  load()
}

watch(() => query.keyword, () => {
  query.page = 1
  load()
})

onMounted(load)

const fmtKb = (n?: number) => `${(n ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} KB`
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.hint {
  color: #98a2b0;
  font-size: 12.5px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.strong {
  color: #1f2d3d;
  font-weight: 600;
}

.dim {
  color: #98a2b0;
  font-size: 12px;
}
</style>
