<template>
  <div class="page-card">
    <div class="hint">
      绑定即把某个个人空间接入知识库应用的对话检索：应用内提问时会从绑定的库里召回切片。
      仅平台空间可被绑定，个人空间由用户所有、不出现在候选中。
    </div>
    <div class="toolbar">
      <el-select
        v-model="selectedSpaceId"
        placeholder="选择要绑定的平台空间"
        style="width: 300px"
        filterable
        clearable
      >
        <el-option
          v-for="s in candidates"
          :key="s.id"
          :label="boundIds.has(s.id) ? `${s.name}（已绑定）` : s.name"
          :value="s.id"
          :disabled="boundIds.has(s.id)"
        />
      </el-select>
      <el-button type="primary" :loading="binding" :disabled="!selectedSpaceId" @click="submitBind">绑定</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="spaceName" label="空间" min-width="170">
        <template #default="{ row }">
          <span class="strong">{{ row.spaceName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="空间 ID" width="100">
        <template #default="{ row }">
          <span class="mono">{{ row.spaceId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="docCount" label="文档数" width="100" align="center" />
      <el-table-column prop="createdAt" label="绑定时间" width="170">
        <template #default="{ row }">
          <span class="dim">{{ row.createdAt }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" plain @click="removeBinding(row)">解绑</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无绑定关系" /></template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { kbAdminApi, loadPlatformSpaces } from '../../api/kbAdmin'
import type { KbBindingRow } from '../../api/kbAdmin'

const rows = ref<KbBindingRow[]>([])
const candidates = ref<Array<{ id: number; name: string }>>([])
const selectedSpaceId = ref<number | null>(null)
const loading = ref(false)
const binding = ref(false)

const boundIds = computed(() => new Set(rows.value.map(r => r.spaceId)))

async function load() {
  loading.value = true
  try {
    const [bindings, spaces] = await Promise.all([
      kbAdminApi.bindings(),
      loadPlatformSpaces(),
    ])
    rows.value = bindings
    candidates.value = spaces
  } finally {
    loading.value = false
  }
}

async function submitBind() {
  if (!selectedSpaceId.value) return
  binding.value = true
  try {
    // 非平台空间 400，message 由 request 透出
    await kbAdminApi.bind(selectedSpaceId.value)
    ElMessage.success('绑定成功，应用内即可检索该空间')
    selectedSpaceId.value = null
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '绑定失败')
  } finally {
    binding.value = false
  }
}

async function removeBinding(row: KbBindingRow) {
  try {
    await ElMessageBox.confirm(
      `确定要解绑空间「${row.spaceName}」吗？解绑后应用内将无法再检索该库。`,
      '解绑空间',
      { confirmButtonText: '确定解绑', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await kbAdminApi.unbind(row.spaceId)
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解绑失败')
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

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
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
