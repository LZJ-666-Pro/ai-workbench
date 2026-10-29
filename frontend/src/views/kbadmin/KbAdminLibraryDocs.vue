<template>
  <div class="page-card">
    <!-- 页头：返回 + 库标识。库名/所有者经 query 透传仅用于展示，刷新不丢（在 URL 里） -->
    <div class="docs-head">
      <el-button link @click="router.push('/kb-admin/libraries')">
        <el-icon><Back /></el-icon><span>返回库列表</span>
      </el-button>
      <div class="docs-title">
        <span class="lib-name">{{ libName }}</span>
        <span v-if="owner" class="lib-owner">所有者：{{ owner }}</span>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <!-- 抓取来源的文档可点击回源 -->
          <a v-if="row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener" class="doc-link">{{ row.title }}</a>
          <span v-else class="strong">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="docType" label="类型" width="80">
        <template #default="{ row }"><span class="mono">{{ row.docType }}</span></template>
      </el-table-column>
      <el-table-column label="大小" width="100" align="right">
        <template #default="{ row }">{{ fmtKb(row.sizeKb) }}</template>
      </el-table-column>
      <el-table-column prop="words" label="字数" width="90" align="right" />
      <el-table-column prop="chunks" label="切片" width="80" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[docMeta(row.status).cls]" effect="plain" size="small">
            {{ docMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <!-- 禁用即从检索中摘除（文档保留）；失败时回滚开关 -->
          <el-switch v-model="row.enabled" :disabled="row.status === 'PARSING'" @change="toggleDoc(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" plain @click="removeDoc(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="该库暂无文档" /></template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
/**
 * 用户库治理的文档下钻页（甲方要求：独立页面替代右侧抽屉）。
 * 空间 id 来自路径参数，接口对非个人空间返回 404——治理操作天然被约束在个人库范围内。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back } from '@element-plus/icons-vue'
import { kbAdminApi } from '../../api/kbAdmin'
import type { KbAdminDoc } from '../../api/kbAdmin'
import { DOC_STATUS_META } from '../../api/kb'
import type { DocStatus } from '../../api/kb'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.spaceId)
const libName = computed(() => (route.query.name as string) || '文档列表')
const owner = (route.query.owner as string) || ''

const rows = ref<KbAdminDoc[]>([])
const loading = ref(false)

/** 状态字典沿用 kb.ts 的 DOC_STATUS_META（个人库状态集合一致），cls 映射到 el-tag type */
const STATUS_TAG: Record<string, 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  building: 'warning',
  error: 'danger',
}
const docMeta = (status: DocStatus) => DOC_STATUS_META[status]

async function load() {
  loading.value = true
  try {
    // 非个人库接口 404，message 由 request 透出
    rows.value = await kbAdminApi.libraryDocuments(spaceId)
  } finally {
    loading.value = false
  }
}

async function toggleDoc(row: KbAdminDoc) {
  try {
    await kbAdminApi.setDocEnabled(row.id, row.enabled)
    ElMessage.success(row.enabled ? '已启用，文档重新参与检索' : '已禁用，文档从检索中摘除')
  } catch (e) {
    // 失败回滚开关，保持与后端状态一致
    row.enabled = !row.enabled
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function removeDoc(row: KbAdminDoc) {
  try {
    await ElMessageBox.confirm(
      `确定要删除文档「${row.title}」吗？其切片将一并清除，删除后不可恢复。`,
      '删除文档',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const r = await kbAdminApi.deleteDoc(row.id)
    ElMessage.success(r.message || '文档已删除')
    // 本页重查保持列表真实；返回库列表时那边 onMounted 重查，计数自然对齐
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(load)

const fmtKb = (n?: number) => `${(n ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} KB`
</script>

<style scoped>
.docs-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
}

.docs-head .el-button {
  color: #8a97a8;
  font-size: 13px;
}

.docs-head .el-button:hover {
  color: #0b4f9e;
}

.docs-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.lib-name {
  font-size: 16px;
  font-weight: 700;
  color: #1f2d3d;
}

.lib-owner {
  font-size: 12.5px;
  color: #98a2b0;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.strong {
  color: #1f2d3d;
  font-weight: 600;
}

.doc-link {
  color: #0b4f9e;
  text-decoration: none;
}

.doc-link:hover {
  text-decoration: underline;
}
</style>
