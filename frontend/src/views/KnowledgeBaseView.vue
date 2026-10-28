<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, Plus, Search, ArrowRight, MoreFilled, UploadFilled } from '@element-plus/icons-vue'
import {
  loadKbSpaces, createKbSpace, updateKbSpace, deleteKbSpace, rebuildKbSpace, uploadKbDocuments,
  KB_STATUS_META,
  type KbSpace,
} from '../api/kb'
import { loadWorkbench, type Workbench } from '../api/platform'
import ContextBar from '../components/ContextBar.vue'
import PlatformBar from '../components/PlatformBar.vue'

const router = useRouter()

const spaces = ref<KbSpace[]>([])
const workbench = ref<Workbench | null>(null)
const error = ref('')
const loading = ref(false)

const EMPTY_PLATFORM = {
  eventsToday: 0, avgLatencyMs: null, successRate: 0, endpoints: 0,
  apps: 0, sessionsToday: 0, tokensToday: 0, updatedAt: '—',
}
const platform = computed(() => workbench.value?.platform ?? EMPTY_PLATFORM)
const hero = computed(() => workbench.value?.hero ?? {
  models: 0, sseP95Ms: null, memorySegments: 0, tools: 0,
})

const AGENT_LABELS: Record<string, string> = {
  bank: '银行助手',
  knowledge: '个人知识库',
  interview: '面试模拟器',
}

// ---------- 搜索与筛选 ----------

const keyword = ref('')
const statusFilter = ref<'ALL' | 'READY' | 'BUILDING' | 'FAILED'>('ALL')

const filtered = computed(() => spaces.value.filter(s => {
  const kw = keyword.value.trim().toLowerCase()
  const matchKw = !kw || s.name.toLowerCase().includes(kw)
    || (s.description ?? '').toLowerCase().includes(kw)
  const matchStatus = statusFilter.value === 'ALL' || s.status === statusFilter.value
  return matchKw && matchStatus
}))

// ---------- 列表加载 ----------

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [list, wb] = await Promise.all([loadKbSpaces(), loadWorkbench()])
    spaces.value = list
    workbench.value = wb
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
  } finally {
    loading.value = false
  }
}

// ---------- 新建 / 编辑 ----------

const dialogVisible = ref(false)
const editingId = ref(0)
const saving = ref(false)

const form = reactive({
  name: '',
  description: '',
  agents: [] as string[],
  chunkSize: 500,
  chunkOverlap: 50,
})

/** 创建时选中的文件（编辑模式不支持传文件，走详情页上传） */
const pendingFiles = ref<File[]>([])

function onFileChange(fileList: Array<{ raw?: File }>) {
  pendingFiles.value = fileList.map(f => f.raw).filter((f): f is File => !!f)
}

function openCreate() {
  editingId.value = 0
  form.name = ''
  form.description = ''
  form.agents = []
  form.chunkSize = 500
  form.chunkOverlap = 50
  pendingFiles.value = []
  dialogVisible.value = true
}

function openEdit(space: KbSpace) {
  editingId.value = space.id
  form.name = space.name
  form.description = space.description ?? ''
  form.agents = [...space.agents]
  form.chunkSize = space.chunkSize
  form.chunkOverlap = space.chunkOverlap
  pendingFiles.value = []
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  saving.value = true
  const payload = {
    name: form.name.trim(),
    description: form.description.trim(),
    chunkSize: form.chunkSize,
    chunkOverlap: form.chunkOverlap,
    agents: form.agents,
  }
  try {
    if (editingId.value) {
      const resp = await updateKbSpace(editingId.value, payload)
      ElMessage.success(resp.message)
      dialogVisible.value = false
      await load()
      return
    }
    const created = await createKbSpace(payload)
    if (pendingFiles.value.length) {
      const uploadResp = await uploadKbDocuments(created.id, pendingFiles.value)
      ElMessage.success(uploadResp.message)
    } else {
      ElMessage.success(created.message)
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 行内操作 ----------

async function rebuild(space: KbSpace) {
  try {
    const resp = await rebuildKbSpace(space.id)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重建失败')
  }
}

async function remove(space: KbSpace) {
  try {
    await ElMessageBox.confirm(
      `删除「${space.name}」将同时移除 ${space.docCount} 篇文档与全部切片，绑定关系一并解除。`,
      '删除知识库',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const resp = await deleteKbSpace(space.id)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

const fmtTime = (value: string | null) => (value ? value.slice(0, 16) : '—')
const statusOf = (s: KbSpace['status']) => KB_STATUS_META[s] ?? KB_STATUS_META.BUILDING

onMounted(load)
</script>

<template>
  <section class="kb-page">
    <ContextBar :hero="hero" />

    <div class="page-body">
      <div class="section-head">
        <div class="head-text">
          <h2 class="section-title">知识库</h2>
          <p class="section-sub">把企业文档变成可检索的知识，供绑定的 Agent 引用作答。</p>
        </div>
        <el-button type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>新建知识库
        </el-button>
      </div>

      <el-alert
        v-if="error"
        class="load-error"
        type="warning"
        :title="`知识库列表加载失败：${error}`"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>

      <!-- 搜索 + 状态筛选 -->
      <div class="toolbar">
        <el-input v-model="keyword" class="search-input" placeholder="搜索知识库名称或描述" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-radio-group v-model="statusFilter">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="READY">就绪</el-radio-button>
          <el-radio-button value="BUILDING">构建中</el-radio-button>
          <el-radio-button value="FAILED">失败</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 卡片视图 -->
      <div v-if="filtered.length" class="card-grid">
        <div v-for="space in filtered" :key="space.id" class="kb-card">
          <div class="card-top">
            <div class="card-id">
              <span class="kb-tile"><el-icon><Collection /></el-icon></span>
              <div class="card-name-main">
                <div class="card-name" @click="router.push(`/knowledgebases/${space.id}`)">{{ space.name }}</div>
                <span class="status-badge" :class="statusOf(space.status).cls">
                  <i class="dot" />{{ statusOf(space.status).label }}
                </span>
              </div>
            </div>
            <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'edit' ? openEdit(space) : cmd === 'rebuild' ? rebuild(space) : remove(space)">
              <span class="more-btn"><el-icon><MoreFilled /></el-icon></span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="rebuild">重建索引</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div class="card-desc">{{ space.description || '暂无描述' }}</div>
          <div class="card-metrics">
            <span class="metric"><b>{{ space.docCount }}</b>文档</span>
            <span class="metric"><b>{{ space.chunkTotal }}</b>切片</span>
            <span class="metric">
              <b>{{ space.agents.length }}</b>绑定 Agent
            </span>
          </div>
          <div class="card-agents" v-if="space.agents.length">
            <span v-for="a in space.agents" :key="a" class="agent-chip">{{ AGENT_LABELS[a] ?? a }}</span>
          </div>
          <div class="card-foot">
            <span class="card-time">更新于 {{ fmtTime(space.updatedAt) }}</span>
            <el-button class="enter-btn" @click="router.push(`/knowledgebases/${space.id}`)">
              进入<el-icon class="go-icon"><ArrowRight /></el-icon>
            </el-button>
          </div>
        </div>
      </div>

      <!-- 空态引导 -->
      <div v-else-if="!loading && !error" class="empty-guide">
        <el-empty description="还没有知识库">
          <template #description>
            <p class="empty-text">还没有知识库，创建第一个知识库，让 Agent 具备企业知识能力。</p>
            <el-button type="primary" @click="openCreate">
              <el-icon class="btn-icon"><Plus /></el-icon>新建知识库
            </el-button>
          </template>
        </el-empty>
      </div>

      <div v-if="loading" class="loading-tip">加载中…</div>
    </div>

    <PlatformBar :platform="platform" :running-count="workbench?.apps?.length ?? 0" />

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑知识库' : '新建知识库'" width="620px">
      <el-form label-width="92px" label-position="left">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：零售业务制度库" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" placeholder="这个知识库存什么、给谁用（可选）" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="上传文档">
          <el-upload
            class="upload-box"
            drag
            multiple
            :auto-upload="false"
            :show-file-list="true"
            :on-change="onFileChange"
            accept=".txt,.md,.csv,.pdf,.docx"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 txt / md / csv（pdf、docx 第二期支持解析）；可多选，创建后自动解析切片</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="绑定 Agent">
          <el-select v-model="form.agents" multiple placeholder="选择具备该知识能力的应用" style="width: 100%">
            <el-option value="bank" label="银行助手" />
            <el-option value="knowledge" label="个人知识库" />
            <el-option value="interview" label="面试模拟器" />
          </el-select>
        </el-form-item>
        <el-collapse class="advanced">
          <el-collapse-item title="高级配置（切片与向量化）" name="adv">
            <div class="adv-row">
              <span class="adv-label">切片大小（字符）</span>
              <el-input-number v-model="form.chunkSize" :min="200" :max="2000" :step="100" />
            </div>
            <div class="adv-row">
              <span class="adv-label">切片重叠（字符）</span>
              <el-input-number v-model="form.chunkOverlap" :min="0" :max="500" :step="10" />
            </div>
            <div class="adv-hint">变更后对已有文档执行「重建索引」才会生效；Embedding 模型将在向量能力接入后开放选择。</div>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.kb-page {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;

  min-height: calc(100vh - 56px);
  display: flex;
  flex-direction: column;
  background: #f7f9fb;
}

.page-body {
  flex: 1;
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 22px 24px 26px;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.head-text { min-width: 0; }
.section-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #1f2d3d;
  letter-spacing: 0.5px;
}
.section-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8a97a8;
}
.section-head .el-button .btn-icon { margin-right: 5px; }

.load-error { margin-bottom: 16px; }

/* 搜索 + 筛选工具行 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 18px;
}
.search-input { max-width: 320px; }

/* 卡片墙 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
}
.kb-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: box-shadow 0.15s, border-color 0.15s;
}
.kb-card:hover {
  border-color: #b6bcc6;
  box-shadow: 0 4px 14px rgba(11, 79, 158, 0.10);
}

.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.card-id { display: flex; align-items: center; gap: 12px; min-width: 0; }
.kb-tile {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #e6f0fb, #d2e4f7);
  color: #0b4f9e;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}
.card-name-main { display: flex; flex-direction: column; gap: 5px; align-items: flex-start; }
.card-name {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  cursor: pointer;
}
.card-name:hover { color: #0b4f9e; }

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 5px;
  padding: 2px 8px;
  white-space: nowrap;
}
.status-badge .dot { width: 6px; height: 6px; border-radius: 50%; }
.status-badge.ok { color: #0a8f3c; background: #eaf8f0; }
.status-badge.ok .dot { background: #0a8f3c; }
.status-badge.building { color: #0b4f9e; background: #e9f1f9; }
.status-badge.building .dot { background: #0b4f9e; }
.status-badge.error { color: #c0392b; background: #fef2f2; }
.status-badge.error .dot { background: #c0392b; }

.more-btn {
  cursor: pointer;
  color: #8a97a8;
  font-size: 16px;
  padding: 2px 4px;
  border-radius: 5px;
}
.more-btn:hover { color: #0b4f9e; background: #f2f7fc; }

.card-desc {
  font-size: 12.5px;
  color: #8a97a8;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 20px;
}

.card-metrics { display: flex; gap: 18px; }
.metric { font-size: 12px; color: #98a2b0; display: inline-flex; align-items: baseline; gap: 4px; }
.metric b {
  font-size: 16px;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
}

.card-agents { display: flex; flex-wrap: wrap; gap: 6px; }
.agent-chip {
  font-size: 11.5px;
  color: #4a5a6d;
  background: #f4f6f9;
  border: 1px solid #e2e7ee;
  border-radius: 999px;
  padding: 2px 10px;
}

.card-foot {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid #f2f4f7;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-time { font-size: 11.5px; color: #b6bcc6; }
.enter-btn {
  background: #fff;
  border-color: #b9cee3;
  color: #0b4f9e;
  padding: 6px 14px;
}
.enter-btn:hover {
  background: #f2f7fc;
  border-color: #0b4f9e;
  color: #0b4f9e;
}
.go-icon { margin-left: 3px; }

/* 空态 / 加载 */
.empty-guide {
  background: #fff;
  border: 1px dashed #e5e8ec;
  border-radius: 12px;
  padding: 40px 0;
}
.empty-text {
  margin: 0 0 14px;
  font-size: 13.5px;
  color: #4a5a6d;
}
.loading-tip {
  text-align: center;
  color: #8a97a8;
  font-size: 13px;
  padding: 30px 0;
}

/* 新建对话框 */
.upload-box { width: 100%; }
.upload-icon { font-size: 34px; color: #79a5d4; margin-bottom: 6px; }
.advanced { margin-left: 8px; border-top: 1px dashed #eceff3; }
.adv-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.adv-label { font-size: 12.5px; color: #4a5a6d; min-width: 110px; }
.adv-hint {
  font-size: 12px;
  color: #98a2b0;
  line-height: 1.6;
  background: #f7f9fb;
  border-radius: 6px;
  padding: 8px 10px;
}
</style>
