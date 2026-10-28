<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Upload, RefreshRight, Delete, Search, Setting } from '@element-plus/icons-vue'
import {
  loadKbSpace, updateKbSpace, deleteKbSpace, uploadKbDocuments,
  updateKbDocEnabled, deleteKbDoc, rebuildKbSpace, searchKbSpace,
  KB_STATUS_META, DOC_STATUS_META,
  type KbSpaceDetail, type KbSearchResult, type KbDoc,
} from '../api/kb'

const route = useRoute()
const router = useRouter()

const spaceId = computed(() => String(route.params.id))
const detail = ref<KbSpaceDetail | null>(null)
const error = ref('')
const loading = ref(false)

const AGENT_LABELS: Record<string, string> = {
  bank: '银行助手（全域）',
  'bank:RETAIL': '银行助手 · 仅零售',
  'bank:CORPORATE': '银行助手 · 仅对公',
  'bank:STAFF': '银行助手 · 仅员工',
  knowledge: '个人知识库',
  interview: '面试模拟器',
}

const space = computed(() => detail.value?.space ?? null)
const documents = computed(() => detail.value?.documents ?? [])
const statusOf = computed(() => (space.value ? KB_STATUS_META[space.value.status] : KB_STATUS_META.BUILDING))

const headMetrics = computed(() => [
  { label: '文档数', value: String(space.value?.docCount ?? 0) },
  { label: '切片数', value: String(space.value?.chunkTotal ?? 0) },
  { label: '绑定 Agent', value: String(space.value?.agents.length ?? 0) },
])

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await loadKbSpace(spaceId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
  } finally {
    loading.value = false
  }
}

// ---------- Tab：文档管理 ----------

const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function pickFiles() {
  fileInput.value?.click()
}

async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (!files.length) return
  uploading.value = true
  try {
    const resp = await uploadKbDocuments(spaceId.value, files)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function toggleDoc(doc: KbDoc) {
  try {
    const resp = await updateKbDocEnabled(spaceId.value, doc.id, !doc.enabled)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function removeDoc(doc: KbDoc) {
  try {
    await ElMessageBox.confirm(
      `删除「${doc.title}」及其 ${doc.chunks} 个切片。`,
      '删除文档',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const resp = await deleteKbDoc(spaceId.value, doc.id)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- Tab：检索测试 ----------

const question = ref('')
const searching = ref(false)
const searchResult = ref<KbSearchResult | null>(null)
const searchError = ref('')

async function doSearch() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入要测试的问题')
    return
  }
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await searchKbSpace(spaceId.value, question.value.trim())
  } catch (e) {
    searchError.value = e instanceof Error ? e.message : '检索失败'
  } finally {
    searching.value = false
  }
}

// ---------- 头部：重建 / 编辑 / 删除 ----------

const rebuilding = ref(false)

async function rebuild() {
  rebuilding.value = true
  try {
    const resp = await rebuildKbSpace(spaceId.value)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重建失败')
  } finally {
    rebuilding.value = false
  }
}

const editVisible = ref(false)
const saving = ref(false)
const form = reactive({
  name: '',
  description: '',
  agents: [] as string[],
  chunkSize: 500,
  chunkOverlap: 50,
})

function openEdit() {
  if (!space.value) return
  form.name = space.value.name
  form.description = space.value.description ?? ''
  form.agents = [...space.value.agents]
  form.chunkSize = space.value.chunkSize
  form.chunkOverlap = space.value.chunkOverlap
  editVisible.value = true
}

async function saveEdit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  saving.value = true
  try {
    const resp = await updateKbSpace(spaceId.value, {
      name: form.name.trim(),
      description: form.description.trim(),
      chunkSize: form.chunkSize,
      chunkOverlap: form.chunkOverlap,
      agents: form.agents,
    })
    ElMessage.success(resp.message)
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeSpace() {
  if (!space.value) return
  try {
    await ElMessageBox.confirm(
      `删除「${space.value.name}」将同时移除 ${space.value.docCount} 篇文档与全部切片。`,
      '删除知识库',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteKbSpace(spaceId.value)
    ElMessage.success('知识库已删除')
    router.push('/knowledgebases')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

const fmtTime = (value: string | null) => (value ? value.slice(0, 16) : '—')
const docStatusOf = (s: KbDoc['status']) => DOC_STATUS_META[s] ?? DOC_STATUS_META.PARSING

onMounted(load)
</script>

<template>
  <section class="kbd-page">
    <div class="page-body">
      <div class="back-row">
        <el-button link type="primary" @click="router.push('/knowledgebases')">
          <el-icon class="l-icon"><ArrowLeft /></el-icon>返回知识库列表
        </el-button>
      </div>

      <el-alert v-if="error" class="load-error" type="warning" :title="error" :closable="false" show-icon>
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>

      <div v-else-if="space" class="detail-inner">
        <!-- 概览头部 -->
        <div class="head">
          <div class="head-main">
            <div class="head-title-row">
              <h2 class="head-title">{{ space.name }}</h2>
              <span class="status-badge" :class="statusOf.cls"><i class="dot" />{{ statusOf.label }}</span>
            </div>
            <p class="head-desc">{{ space.description || '暂无描述' }}</p>
            <div class="head-meta">登记人 {{ space.owner }} · 创建于 {{ fmtTime(space.createdAt) }} · 切片 {{ space.chunkSize }} 字 / 重叠 {{ space.chunkOverlap }}</div>
          </div>
          <div class="head-side">
            <div class="head-metrics">
              <div v-for="m in headMetrics" :key="m.label" class="head-metric">
                <span class="v">{{ m.value }}</span>
                <span class="l">{{ m.label }}</span>
              </div>
            </div>
            <div class="head-actions">
              <el-button class="outline-btn" :loading="rebuilding" @click="rebuild">
                <el-icon class="l-icon"><RefreshRight /></el-icon>重建索引
              </el-button>
              <el-button class="outline-btn" @click="openEdit">编辑</el-button>
              <el-button class="outline-btn danger" @click="removeSpace">删除</el-button>
            </div>
          </div>
        </div>

        <el-tabs class="detail-tabs" model-value="docs">
          <!-- Tab 1：文档管理 -->
          <el-tab-pane label="文档管理" name="docs">
            <div class="tab-toolbar">
              <span class="tab-hint">切片是检索的最小单位；禁用的文档不参与检索。</span>
              <el-button type="primary" :loading="uploading" @click="pickFiles">
                <el-icon class="btn-icon"><Upload /></el-icon>上传文档
              </el-button>
              <input
                ref="fileInput" type="file" multiple style="display: none"
                accept=".txt,.md,.csv,.pdf,.docx" @change="onFilesPicked"
              >
            </div>
            <div class="doc-table">
              <div class="doc-head">
                <span>文档</span><span>类型</span><span>大小</span><span>字数</span>
                <span>切片</span><span>解析状态</span><span>参与检索</span><span class="ta-right">操作</span>
              </div>
              <div v-for="doc in documents" :key="doc.id" class="doc-row">
                <div class="doc-title" :title="doc.errorMsg ?? doc.title">
                  {{ doc.title }}
                  <div v-if="doc.errorMsg" class="doc-err">{{ doc.errorMsg }}</div>
                </div>
                <span class="doc-type">{{ doc.docType.toUpperCase() }}</span>
                <span class="doc-num">{{ doc.sizeKb }} KB</span>
                <span class="doc-num">{{ doc.words.toLocaleString() }}</span>
                <span class="doc-num">{{ doc.chunks }}</span>
                <span>
                  <span class="status-badge" :class="docStatusOf(doc.status).cls">
                    <i class="dot" />{{ docStatusOf(doc.status).label }}
                  </span>
                </span>
                <span>
                  <el-switch :model-value="doc.enabled" @change="toggleDoc(doc)" />
                </span>
                <span class="ta-right">
                  <el-button link type="danger" @click="removeDoc(doc)">删除</el-button>
                </span>
              </div>
              <div v-if="!documents.length" class="doc-empty">暂无文档，点右上角「上传文档」开始构建。</div>
            </div>
          </el-tab-pane>

          <!-- Tab 2：检索测试 -->
          <el-tab-pane label="检索测试" name="search">
            <div class="search-box">
              <el-input
                v-model="question" placeholder="输入问题，如：个人账户转账限额是多少"
                clearable @keyup.enter="doSearch"
              >
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-button type="primary" :loading="searching" @click="doSearch">测试召回</el-button>
            </div>
            <div v-if="searchError" class="search-error">{{ searchError }}</div>
            <template v-if="searchResult">
              <div class="search-meta">
                命中 {{ searchResult.hits.length }} 条 · 耗时 {{ searchResult.tookMs }}ms · {{ searchResult.note }}
              </div>
              <div v-if="searchResult.hits.length" class="hit-list">
                <div v-for="(hit, i) in searchResult.hits" :key="i" class="hit-card">
                  <div class="hit-head">
                    <span class="hit-rank">#{{ i + 1 }}</span>
                    <span class="hit-doc">{{ hit.docTitle }} · 切片 {{ hit.seq }}</span>
                    <span class="hit-score">
                      <span class="score-bar"><i :style="{ width: `${Math.round(hit.score * 100)}%` }" /></span>
                      <b>{{ (hit.score * 100).toFixed(1) }}%</b>
                    </span>
                  </div>
                  <div class="hit-content">{{ hit.content }}</div>
                </div>
              </div>
              <div v-else class="search-empty">没有召回结果：换个问法，或确认相关文档已上传且处于启用状态。</div>
            </template>
            <div v-else-if="!searchError" class="search-empty">输入问题后点「测试召回」，查看命中的切片与相关度分数。</div>
          </el-tab-pane>

          <!-- Tab 3：绑定的 Agent -->
          <el-tab-pane label="绑定 Agent" name="agents">
            <div class="agent-panel">
              <p class="tab-hint">
                绑定的 Agent 在对话时引用本知识库的切片作答。修改绑定请点「编辑」。
              </p>
              <div v-if="space.agents.length" class="agent-grid">
                <div v-for="a in space.agents" :key="a" class="agent-card">
                  <span class="agent-name">{{ AGENT_LABELS[a] ?? a }}</span>
                  <span class="agent-key">{{ a }}</span>
                </div>
              </div>
              <div v-else class="search-empty">还没有绑定任何 Agent。</div>
              <el-button class="outline-btn" @click="openEdit">修改绑定</el-button>
            </div>
          </el-tab-pane>

          <!-- Tab 4：设置 -->
          <el-tab-pane label="设置" name="settings">
            <div class="setting-panel">
              <div class="setting-section">
                <div class="setting-title"><el-icon><Setting /></el-icon>基本信息</div>
                <div class="detail-rows">
                  <div class="detail-row"><span>名称</span><b>{{ space.name }}</b></div>
                  <div class="detail-row"><span>描述</span><b>{{ space.description || '—' }}</b></div>
                  <div class="detail-row"><span>登记人</span><b>{{ space.owner }}</b></div>
                  <div class="detail-row"><span>创建时间</span><b>{{ fmtTime(space.createdAt) }}</b></div>
                  <div class="detail-row"><span>最近更新</span><b>{{ fmtTime(space.updatedAt) }}</b></div>
                </div>
              </div>
              <div class="setting-section">
                <div class="setting-title"><el-icon><Setting /></el-icon>切片与向量化配置</div>
                <div class="detail-rows">
                  <div class="detail-row"><span>切片大小</span><b>{{ space.chunkSize }} 字符</b></div>
                  <div class="detail-row"><span>切片重叠</span><b>{{ space.chunkOverlap }} 字符</b></div>
                  <div class="detail-row"><span>Embedding 模型</span><b>待接入（第二期开放选择）</b></div>
                </div>
                <div class="setting-note">修改配置或配置后想对已有文档生效，请执行「重建索引」；从数据源同步与增量更新将在第二期提供。</div>
              </div>
              <div class="setting-section">
                <div class="setting-title danger"><el-icon><Delete /></el-icon>危险操作</div>
                <div class="danger-row">
                  <span>删除知识库将不可恢复地移除全部文档、切片与绑定关系。</span>
                  <el-button class="outline-btn danger" @click="removeSpace">删除知识库</el-button>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div v-else-if="loading" class="loading-tip">加载中…</div>
    </div>

    <!-- 编辑对话框（基本信息 + 绑定 + 高级配置） -->
    <el-dialog v-model="editVisible" title="编辑知识库" width="560px">
      <el-form label-width="92px" label-position="left">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="绑定 Agent">
          <!-- 银行助手可按服务对象身份区分知识范围：绑定 retail 的空间只有零售会话检索得到 -->
          <el-select v-model="form.agents" multiple style="width: 100%">
            <el-option-group label="银行助手">
              <el-option value="bank" label="全域（所有身份）" />
              <el-option value="bank:RETAIL" label="仅零售客户" />
              <el-option value="bank:CORPORATE" label="仅对公客户" />
              <el-option value="bank:STAFF" label="仅内部员工" />
            </el-option-group>
            <el-option value="knowledge" label="个人知识库" />
            <el-option value="interview" label="面试模拟器" />
          </el-select>
        </el-form-item>
        <el-form-item label="切片配置">
          <div class="adv-inline">
            <span>大小</span>
            <el-input-number v-model="form.chunkSize" :min="200" :max="2000" :step="100" />
            <span>重叠</span>
            <el-input-number v-model="form.chunkOverlap" :min="0" :max="500" :step="10" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.kbd-page {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;

  min-height: calc(100vh - 56px);
  background: #f7f9fb;
}
.page-body {
  max-width: 1280px;
  margin: 0 auto;
  padding: 16px 24px 30px;
}
.back-row { margin-bottom: 8px; }
.l-icon { margin-right: 3px; }
.load-error { margin: 12px 0; }
.loading-tip {
  text-align: center;
  color: #8a97a8;
  font-size: 13px;
  padding: 40px 0;
}

/* 概览头部 */
.head {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 20px 22px;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.head-title-row { display: flex; align-items: center; gap: 12px; }
.head-title {
  margin: 0;
  font-size: 21px;
  font-weight: 800;
  color: #1f2d3d;
}
.head-desc {
  margin: 7px 0 0;
  font-size: 13px;
  color: #8a97a8;
}
.head-meta {
  margin-top: 8px;
  font-size: 12px;
  color: #98a2b0;
}
.head-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 14px;
  flex-shrink: 0;
}
.head-metrics { display: flex; gap: 26px; }
.head-metric { display: flex; flex-direction: column; align-items: flex-end; }
.head-metric .v {
  font-size: 22px;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}
.head-metric .l { font-size: 11.5px; color: #98a2b0; margin-top: 3px; }
.head-actions { display: flex; gap: 8px; }

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 5px;
  padding: 2px 9px;
  white-space: nowrap;
}
.status-badge .dot { width: 6px; height: 6px; border-radius: 50%; }
.status-badge.ok { color: #0a8f3c; background: #eaf8f0; }
.status-badge.ok .dot { background: #0a8f3c; }
.status-badge.building { color: #0b4f9e; background: #e9f1f9; }
.status-badge.building .dot { background: #0b4f9e; }
.status-badge.error { color: #c0392b; background: #fef2f2; }
.status-badge.error .dot { background: #c0392b; }

.outline-btn {
  background: #fff;
  border-color: #b9cee3;
  color: #0b4f9e;
}
.outline-btn:hover {
  background: #f2f7fc;
  border-color: #0b4f9e;
  color: #0b4f9e;
}
.outline-btn.danger { border-color: #e5b6b0; color: #c0392b; }

/* Tabs */
.detail-tabs { margin-top: 18px; }
.detail-tabs :deep(.el-tabs__item.is-active),
.detail-tabs :deep(.el-tabs__item:hover) { color: #0b4f9e; }
.detail-tabs :deep(.el-tabs__active-bar) { background-color: #0b4f9e; }

.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
}
.btn-icon { margin-right: 5px; }
.tab-hint { font-size: 12.5px; color: #98a2b0; }

/* 文档表 */
.doc-table {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  overflow: hidden;
}
.doc-head, .doc-row {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) 64px 76px 90px 64px 96px 86px 70px;
  gap: 12px;
  align-items: center;
  padding: 0 18px;
}
.doc-head {
  height: 42px;
  font-size: 12.5px;
  color: #8a97a8;
  background: #f7f9fb;
  border-bottom: 1px solid #eceff3;
}
.doc-head .ta-right { text-align: right; }
.doc-row {
  padding-top: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f2f4f7;
  font-size: 13px;
  color: #344055;
}
.doc-row:last-child { border-bottom: 0; }
.doc-row:hover { background: #f7f9fb; }
.doc-title { font-weight: 600; color: #1f2d3d; word-break: break-all; }
.doc-err {
  font-weight: 400;
  font-size: 11.5px;
  color: #c0392b;
  margin-top: 3px;
}
.doc-type { font-size: 12px; color: #8a97a8; }
.doc-num { font-variant-numeric: tabular-nums; }
.doc-empty { padding: 26px 0; text-align: center; color: #b6bcc6; font-size: 13px; }

/* 检索测试 */
.search-box { display: flex; gap: 10px; margin-bottom: 12px; }
.search-box .el-input { max-width: 560px; }
.search-meta { font-size: 12px; color: #8a97a8; margin-bottom: 12px; }
.search-error {
  font-size: 13px;
  color: #c0392b;
  background: #fef2f2;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 10px;
}
.search-empty {
  font-size: 13px;
  color: #98a2b0;
  background: #fff;
  border: 1px dashed #e5e8ec;
  border-radius: 8px;
  padding: 22px;
  text-align: center;
}
.hit-list { display: flex; flex-direction: column; gap: 10px; }
.hit-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 8px;
  padding: 12px 14px;
}
.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.hit-rank {
  font-size: 12px;
  font-weight: 700;
  color: #0b4f9e;
  background: #e9f1f9;
  border-radius: 4px;
  padding: 1px 7px;
}
.hit-doc { font-size: 12.5px; color: #4a5a6d; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hit-score { display: inline-flex; align-items: center; gap: 8px; }
.hit-score b { font-size: 12.5px; color: #1f2d3d; font-variant-numeric: tabular-nums; }
.score-bar {
  width: 90px;
  height: 6px;
  border-radius: 3px;
  background: #eef1f5;
  overflow: hidden;
  display: inline-block;
}
.score-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #4f8fd6, #0b4f9e);
}
.hit-content {
  font-size: 12.5px;
  color: #344055;
  line-height: 1.7;
  white-space: pre-wrap;
  background: #f7f9fb;
  border-radius: 6px;
  padding: 10px 12px;
  max-height: 160px;
  overflow: auto;
}

/* 绑定 Agent */
.agent-panel {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: flex-start;
}
.agent-grid { display: flex; gap: 12px; flex-wrap: wrap; }
.agent-card {
  border: 1px solid #e2e7ee;
  border-radius: 8px;
  padding: 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 130px;
}
.agent-name { font-size: 14px; font-weight: 700; color: #1f2d3d; }
.agent-key { font-size: 11.5px; color: #98a2b0; }

/* 设置 */
.setting-panel { display: flex; flex-direction: column; gap: 14px; }
.setting-section {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 16px 20px;
}
.setting-title {
  font-size: 13.5px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.setting-title.danger { color: #c0392b; }
.detail-rows { display: flex; flex-direction: column; gap: 8px; }
.detail-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 12.5px;
  color: #8a97a8;
}
.detail-row b { color: #1f2d3d; font-weight: 600; text-align: right; }
.setting-note {
  margin-top: 10px;
  font-size: 12px;
  color: #98a2b0;
  line-height: 1.6;
  background: #f7f9fb;
  border-radius: 6px;
  padding: 8px 10px;
}
.danger-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  font-size: 12.5px;
  color: #8a97a8;
}

.adv-inline { display: flex; align-items: center; gap: 8px; }
.adv-inline span { font-size: 12.5px; color: #4a5a6d; }

@media (max-width: 900px) {
  .head { flex-direction: column; }
  .head-side { align-items: flex-start; }
  .head-metric { align-items: flex-start; }
}
</style>
