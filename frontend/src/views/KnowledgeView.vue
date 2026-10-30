<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Upload } from '@element-plus/icons-vue'
import ChatWindow from '../components/ChatWindow.vue'
import { isOfflineError } from '../api/offline'
import { auth } from '../api/auth'
import {
  MAX_FILE_SIZE_MB, MAX_FILES_PER_UPLOAD,
  deletePersonalDoc, importPersonalDocFromUrl, loadPersonalDocs, loadPersonalSpace,
  uploadPersonalDocs, DOC_STATUS_META,
  type PersonalDoc, type PersonalSpace,
} from '../api/personalKb'

// ---------- Tab 切换：默认进对话，文档管理按需加载/刷新 ----------

const activeTab = ref<'chat' | 'docs'>('chat')

const space = ref<PersonalSpace | null>(null)
const documents = ref<PersonalDoc[]>([])
const loadError = ref('')
/** 离线（后端未启动/断网）时降级为 info 提示，避免"没起服务就像出了 bug"的误读 */
const offline = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  loadError.value = ''
  offline.value = false
  try {
    const [s, docs] = await Promise.all([loadPersonalSpace(), loadPersonalDocs()])
    space.value = s
    documents.value = docs
  } catch (e) {
    const message = e instanceof Error ? e.message : '未知错误'
    loadError.value = message
    // 个人库接口在 8082（KnowledgeApplication），服务未启动时代理回 500——降级为离线提示
    offline.value = isOfflineError(message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
// 切到「我的文档」时刷新一次：解析状态（PARSING→READY）与计数要跟上后端
watch(activeTab, (tab) => {
  if (tab === 'docs') load()
})

// ---------- 上传文档 ----------

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
    const outcome = await uploadPersonalDocs(files)
    // 逐文件分组回显：成功的汇总条数，失败的逐条透出后端原因
    if (outcome.created.length) {
      ElMessage.success(`成功上传 ${outcome.created.length} 个文档，解析完成后即可检索`)
    }
    for (const f of outcome.failed) {
      ElMessage.error(`${f.title}：${f.message}`)
    }
    await load()
  } catch (e) {
    // 超过数量/大小上限等本地校验失败也走这里
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
    input.value = ''
  }
}

// ---------- 网页抓取 ----------

const pageUrl = ref('')
const grabbing = ref(false)

async function grabUrl() {
  const url = pageUrl.value.trim()
  if (!url) {
    ElMessage.warning('请先粘贴网页链接')
    return
  }
  grabbing.value = true
  try {
    const res = await importPersonalDocFromUrl(url)
    ElMessage.success(`已提交抓取「${res.document.title}」，解析完成后即可检索`)
    pageUrl.value = ''
    await load()
  } catch (e) {
    // 400（URL 非法等）的 message 由 API 层透出
    ElMessage.error(e instanceof Error ? e.message : '抓取失败')
  } finally {
    grabbing.value = false
  }
}

// ---------- 删除文档 ----------

async function removeDoc(doc: PersonalDoc) {
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
    const res = await deletePersonalDoc(doc.id)
    ElMessage.success(res.message || '文档已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

const docStatusOf = (s: PersonalDoc['status']) => DOC_STATUS_META[s] ?? DOC_STATUS_META.PARSING
</script>

<template>
  <section class="kb-app">
    <el-tabs v-model="activeTab" class="kb-app-tabs">
      <!-- Tab 1：对话（identity 传登录用户名，memoryId 成 knowledge:{username}:{uuid} 按用户隔离会话） -->
      <el-tab-pane label="对话" name="chat">
        <ChatWindow
          base-path="/knowledge"
          agent="knowledge"
          enable-identity
          :identity="auth.user?.username"
          title="📚 个人知识库"
          welcome="我是你的个人知识库助手，既能检索你上传的文档，也能查平台知识库。先在「我的文档」里上传文件或粘贴网页链接，然后直接问我。"
          placeholder="问我点什么，例如：总结我上传的笔记里关于 LangGraph 路由的内容"
        />
      </el-tab-pane>

      <!-- Tab 2：我的文档 -->
      <el-tab-pane label="我的文档" name="docs">
        <div class="docs-pane">
          <div class="docs-inner">
            <el-alert v-if="loadError && offline" class="load-error" type="info"
              title="个人知识库暂不可用：服务所在后端可能未启动（需启动 KnowledgeApplication / 8082）" :closable="false" show-icon>
              <template #default>
                <el-button link type="primary" @click="load">重试</el-button>
              </template>
            </el-alert>
            <el-alert v-else-if="loadError" class="load-error" type="warning" :title="loadError" :closable="false" show-icon>
              <template #default>
                <el-button link type="primary" @click="load">重试</el-button>
              </template>
            </el-alert>

            <template v-else>
              <!-- 概况 + 上传/抓取入口 -->
              <div class="docs-head">
                <div class="docs-main">
                  <h2 class="docs-title">{{ space?.name ?? '我的知识库' }}</h2>
                  <span class="docs-meta">文档 {{ space?.docCount ?? 0 }} · 切片 {{ space?.chunkTotal ?? 0 }}</span>
                </div>
                <div class="docs-actions">
                  <el-input
                    v-model="pageUrl" class="url-input" clearable
                    placeholder="粘贴网页链接抓取为文档，如 https://example.com/article"
                    @keyup.enter="grabUrl"
                  >
                    <template #prefix><el-icon><Link /></el-icon></template>
                  </el-input>
                  <el-button class="outline-btn" :loading="grabbing" @click="grabUrl">抓取网页</el-button>
                  <el-button type="primary" :loading="uploading" @click="pickFiles">
                    <el-icon class="btn-icon"><Upload /></el-icon>上传文档
                  </el-button>
                  <input
                    ref="fileInput" type="file" multiple style="display: none"
                    accept=".txt,.md,.csv,.pdf,.doc,.docx,.html,.htm" @change="onFilesPicked"
                  >
                </div>
              </div>
              <p class="docs-hint">
                支持 txt / md / csv / pdf / doc / docx / html / htm，单文件不超过 {{ MAX_FILE_SIZE_MB }}MB，单次最多 {{ MAX_FILES_PER_UPLOAD }} 个。
              </p>

              <!-- 文档列表 -->
              <div class="doc-table">
                <div class="doc-head">
                  <span>文档</span><span>类型</span><span>大小</span><span>切片</span><span>解析状态</span><span class="ta-right">操作</span>
                </div>
                <div v-for="doc in documents" :key="doc.id" class="doc-row">
                  <div class="doc-title">
                    <!-- 抓取来源的文档标题可点击回源 -->
                    <a
                      v-if="doc.sourceUrl" class="doc-link" :href="doc.sourceUrl"
                      target="_blank" rel="noopener noreferrer" :title="`打开来源网页：${doc.sourceUrl}`"
                    >{{ doc.title }}</a>
                    <template v-else>{{ doc.title }}</template>
                  </div>
                  <span class="doc-type">{{ doc.docType.toUpperCase() }}</span>
                  <span class="doc-num">{{ doc.sizeKb }} KB</span>
                  <span class="doc-num">{{ doc.chunks }}</span>
                  <span>
                    <span class="status-badge" :class="docStatusOf(doc.status).cls">
                      <i class="dot" />{{ docStatusOf(doc.status).label }}
                    </span>
                  </span>
                  <span class="ta-right">
                    <el-button link type="danger" @click="removeDoc(doc)">删除</el-button>
                  </span>
                </div>
                <div v-if="!documents.length" class="doc-empty">
                  还没有文档：点右上角「上传文档」选择本地文件，或粘贴网页链接抓取，上传后即可在对话里检索你的资料。
                </div>
              </div>
            </template>

            <div v-if="loading" class="loading-tip">加载中…</div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
/* 与 KbDetailView 同款的低饱和银行蓝：让 el-tabs / el-button / 徽章统一 */
.kb-app {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;

  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

/* Tabs：占满整页高度，内容区弹性伸展（对话页 ChatWindow 需要 100% 高度） */
.kb-app-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.kb-app-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
  flex-shrink: 0;
}
.kb-app-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
.kb-app-tabs :deep(.el-tab-pane) {
  height: 100%;
}

/* ===== 我的文档 ===== */
.docs-pane {
  height: 100%;
  overflow-y: auto;
  background: #f7f9fb;
}
.docs-inner {
  max-width: 1080px;
  margin: 0 auto;
  padding: 18px 24px 30px;
}
.load-error { margin-bottom: 12px; }
.loading-tip {
  text-align: center;
  color: #8a97a8;
  font-size: 13px;
  padding: 24px 0;
}

.docs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.docs-main { display: flex; align-items: baseline; gap: 12px; }
.docs-title {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
  color: #1f2d3d;
}
.docs-meta { font-size: 12.5px; color: #98a2b0; }
.docs-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.url-input { width: 340px; }
.btn-icon { margin-right: 5px; }
.docs-hint {
  margin: 8px 0 12px;
  font-size: 12px;
  color: #98a2b0;
}

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

/* 文档表（列结构沿用 KbDetailView 的网格表） */
.doc-table {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  overflow: hidden;
}
.doc-head, .doc-row {
  display: grid;
  grid-template-columns: minmax(0, 2.2fr) 70px 84px 70px 96px 76px;
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
.doc-link { color: #0b4f9e; text-decoration: none; }
.doc-link:hover { text-decoration: underline; }
.doc-type { font-size: 12px; color: #8a97a8; }
.doc-num { font-variant-numeric: tabular-nums; }
.doc-empty { padding: 26px 0; text-align: center; color: #b6bcc6; font-size: 13px; }

/* 状态徽章（与 KbDetailView 同款：低饱和实色底 + 圆点） */
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

@media (max-width: 900px) {
  .url-input { width: 100%; }
}
</style>
