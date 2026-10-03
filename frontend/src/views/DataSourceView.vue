<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Plus, Refresh, Connection, Delete, Edit } from '@element-plus/icons-vue'
import {
  loadDataSources, createDataSource, updateDataSource, deleteDataSource,
  testDataSource, syncDataSource,
  TYPE_LABELS, ENGINE_LABELS,
  type DataSource, type SourceType, type SourceStatus,
} from '../api/datasource'
import { loadWorkbench, probeAppRunning, type Workbench } from '../api/platform'
import ContextBar from '../components/ContextBar.vue'
import PlatformBar from '../components/PlatformBar.vue'

const sources = ref<DataSource[]>([])
const workbench = ref<Workbench | null>(null)
const error = ref('')

/** 绑定 Agent 的候选：与平台应用目录一致（APP_LABELS 同款） */
const AGENT_OPTIONS = [
  { key: 'bank', label: '银行助手' },
  { key: 'knowledge', label: '个人知识库' },
  { key: 'interview', label: '面试模拟器' },
]

const EMPTY_PLATFORM = {
  eventsToday: 0, avgLatencyMs: null, successRate: 0, endpoints: 0,
  apps: 0, sessionsToday: 0, tokensToday: 0, updatedAt: '—',
}
const platform = computed(() => workbench.value?.platform ?? EMPTY_PLATFORM)
const hero = computed(() => workbench.value?.hero ?? {
  models: 0, sseP95Ms: null, memorySegments: 0, tools: 0,
})

/** 顶部统计：全部来自登记数据，不编数字 */
const stats = computed(() => [
  { label: '数据源总数', value: String(sources.value.length) },
  { label: '连接正常', value: String(sources.value.filter(s => s.status === 'OK').length) },
  { label: '连接异常', value: String(sources.value.filter(s => s.status === 'ERROR').length) },
  { label: 'Agent 绑定', value: String(sources.value.reduce((n, s) => n + s.bindings.length, 0)) },
])

/** 应用在线探测（仅用于底部状态条的"运行中应用"），不阻塞列表渲染 */
const probedRunning = reactive<Record<string, boolean>>({})
const runningCount = computed(() => Object.values(probedRunning).filter(Boolean).length)

async function probeStatuses() {
  const list = workbench.value?.apps ?? []
  await Promise.all(list.map(async app => {
    probedRunning[app.key] = await probeAppRunning(app.to, app.key)
  }))
}

async function load() {
  error.value = ''
  try {
    const [list, wb] = await Promise.all([loadDataSources(), loadWorkbench()])
    sources.value = list
    workbench.value = wb
    void probeStatuses()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '未知错误'
  }
}

// ---------- 新建 / 编辑 ----------

const dialogVisible = ref(false)
const saving = ref(false)
/** 0 = 新建；>0 = 编辑该数据源 */
const editingId = ref(0)

const form = reactive({
  name: '',
  type: 'DATABASE' as SourceType,
  engine: 'mysql',
  description: '',
  config: {} as Record<string, string>,
  agentKeys: [] as string[],
  scope: 'READ' as 'READ' | 'WRITE',
})

/** 子类型随主类型联动 */
const ENGINE_OPTIONS: Record<SourceType, { value: string; label: string }[]> = {
  DOCUMENT: [
    { value: 'oss', label: 'OSS 对象存储' },
    { value: 'file', label: '本地路径' },
  ],
  DATABASE: [
    { value: 'mysql', label: 'MySQL' },
    { value: 'postgresql', label: 'PostgreSQL' },
  ],
  API: [{ value: 'http', label: 'HTTP 接口' }],
  VECTOR: [{ value: 'pgvector', label: 'pgvector' }],
}

/** 每种子类型的连接配置项（type=password 的输入框显示圆点） */
interface ConfigField { key: string; label: string; placeholder?: string; secret?: boolean }
const CONFIG_FIELDS: Record<string, ConfigField[]> = {
  mysql: [
    { key: 'host', label: '主机', placeholder: 'localhost' },
    { key: 'port', label: '端口', placeholder: '3306' },
    { key: 'database', label: '数据库', placeholder: '库名' },
    { key: 'username', label: '用户名' },
    { key: 'password', label: '密码', secret: true },
  ],
  postgresql: [
    { key: 'host', label: '主机', placeholder: 'localhost' },
    { key: 'port', label: '端口', placeholder: '5432' },
    { key: 'database', label: '数据库', placeholder: '库名' },
    { key: 'username', label: '用户名' },
    { key: 'password', label: '密码', secret: true },
  ],
  pgvector: [
    { key: 'host', label: '主机', placeholder: 'localhost' },
    { key: 'port', label: '端口', placeholder: '5432' },
    { key: 'database', label: '数据库', placeholder: '库名' },
    { key: 'username', label: '用户名' },
    { key: 'password', label: '密码', secret: true },
    { key: 'collection', label: '索引集合', placeholder: '如 kb_docs' },
  ],
  http: [
    { key: 'url', label: '接口地址', placeholder: 'https://host/api/v1/resource' },
  ],
  oss: [
    { key: 'location', label: '存储地址', placeholder: 'https://bucket.oss-region.aliyuncs.com/path 或本地路径' },
    { key: 'format', label: '文档格式（可选）', placeholder: 'pdf / docx / md' },
  ],
  file: [
    { key: 'location', label: '文件路径', placeholder: 'D:/data/docs 或 /data/docs' },
  ],
}
const configFields = computed(() => CONFIG_FIELDS[form.engine] ?? [])

function onTypeChange() {
  form.engine = ENGINE_OPTIONS[form.type][0].value
  form.config = {}
}

function openCreate() {
  editingId.value = 0
  form.name = ''
  form.type = 'DATABASE'
  form.engine = 'mysql'
  form.description = ''
  form.config = {}
  form.agentKeys = []
  form.scope = 'READ'
  dialogVisible.value = true
}

function openEdit(ds: DataSource) {
  editingId.value = ds.id
  form.name = ds.name
  form.type = ds.type
  form.engine = ds.engine
  form.description = ds.description ?? ''
  // 密码返回的是脱敏星号，置空让用户选择是否重填（留空 = 沿用原值）
  const config: Record<string, string> = {}
  for (const [k, v] of Object.entries(ds.config)) {
    config[k] = k === 'password' ? '' : String(v ?? '')
  }
  form.config = config
  form.agentKeys = ds.bindings.map(b => b.agentKey)
  form.scope = ds.bindings[0]?.scope ?? 'READ'
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写数据源名称')
    return
  }
  saving.value = true
  const payload = {
    name: form.name.trim(),
    type: form.type,
    engine: form.engine,
    description: form.description.trim(),
    config: form.config,
    bindings: form.agentKeys.map(key => ({ agentKey: key, scope: form.scope })),
  }
  try {
    const resp = editingId.value
      ? await updateDataSource(editingId.value, payload)
      : await createDataSource(payload)
    ElMessage.success(resp.message)
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 操作 ----------

const testingId = ref(0)
const syncingId = ref(0)

async function test(ds: DataSource) {
  testingId.value = ds.id
  try {
    const result = await testDataSource(ds.id)
    if (result.ok) {
      ElMessage.success(`连接正常（${result.latencyMs}ms）：${result.message}`)
    } else {
      ElMessage.error(`连接失败：${result.message}`)
    }
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    testingId.value = 0
  }
}

async function sync(ds: DataSource) {
  syncingId.value = ds.id
  try {
    const resp = await syncDataSource(ds.id)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    syncingId.value = 0
  }
}

async function remove(ds: DataSource) {
  try {
    await ElMessageBox.confirm(
      `删除后「${ds.name}」的登记与 ${ds.bindings.length} 条 Agent 绑定关系将一并移除。`,
      '删除数据源',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const resp = await deleteDataSource(ds.id)
    ElMessage.success(resp.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- 详情抽屉 ----------

const detailVisible = ref(false)
const detailSource = ref<DataSource | null>(null)

function openDetail(ds: DataSource) {
  detailSource.value = ds
  detailVisible.value = true
}

/** 基础监控：最近探活延迟是真实测量值；调用统计要等 Agent 调用链路接入采集，诚实显示 — */
const metrics = computed(() => [
  { label: '调用次数', value: '—', hint: 'Agent 调用采集接入后展示' },
  { label: '成功率', value: '—', hint: 'Agent 调用采集接入后展示' },
  { label: '最近探活延迟', value: detailSource.value?.lastLatencyMs != null ? `${detailSource.value.lastLatencyMs}ms` : '—', hint: '测试连接时实测' },
])

const CONFIG_LABELS: Record<string, string> = {
  host: '主机', port: '端口', database: '数据库', username: '用户名',
  password: '密码', collection: '索引集合', url: '接口地址',
  location: '存储地址', format: '文档格式', method: '请求方法',
}

function fmtTime(value: string | null) {
  return value ? value.slice(0, 16) : '—'
}

const STATUS_META: Record<SourceStatus, { label: string; cls: string }> = {
  OK: { label: '正常', cls: 'ok' },
  ERROR: { label: '异常', cls: 'error' },
  SYNCING: { label: '同步中', cls: 'syncing' },
  UNTESTED: { label: '未测试', cls: 'untested' },
}
const statusOf = (s: SourceStatus) => STATUS_META[s] ?? STATUS_META.UNTESTED

onMounted(load)
</script>

<template>
  <section class="ds-page">
    <ContextBar :hero="hero" />

    <div class="page-body">
      <div class="section-head">
        <div class="head-text">
          <h2 class="section-title">数据源</h2>
          <p class="section-sub">登记与管理各 Agent 依赖的数据连接，统一探活、绑定与监控。</p>
        </div>
        <el-button type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>新建数据源
        </el-button>
      </div>

      <el-alert
        v-if="error"
        class="load-error"
        type="warning"
        :title="`数据源列表加载失败：${error}`"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>

      <!-- 统计 -->
      <div class="stat-grid">
        <div v-for="s in stats" :key="s.label" class="stat-card">
          <span class="stat-value">{{ s.value }}</span>
          <span class="stat-label">{{ s.label }}</span>
        </div>
      </div>

      <!-- 列表 -->
      <div class="ds-table">
        <div class="table-head">
          <span>名称</span>
          <span>类型</span>
          <span>连接状态</span>
          <span>最近同步</span>
          <span>绑定 Agent</span>
          <span class="ta-right">操作</span>
        </div>
        <div v-for="ds in sources" :key="ds.id" class="table-row">
          <div class="cell-name">
            <span class="ds-tile"><el-icon><Coin /></el-icon></span>
            <div class="ds-name-main">
              <div class="ds-name" @click="openDetail(ds)">{{ ds.name }}</div>
              <div class="ds-desc">{{ ds.description || '暂无描述' }}</div>
            </div>
          </div>
          <div class="cell-type">
            <span class="type-tag">{{ TYPE_LABELS[ds.type] }}</span>
            <span class="engine-tag">{{ ENGINE_LABELS[ds.engine] ?? ds.engine }}</span>
          </div>
          <div>
            <span class="status-badge" :class="statusOf(ds.status).cls">
              <i class="dot" />{{ statusOf(ds.status).label }}
            </span>
            <div class="status-msg" :title="ds.statusMsg ?? undefined">{{ ds.statusMsg || '—' }}</div>
          </div>
          <div class="cell-time">{{ fmtTime(ds.lastSyncAt) }}</div>
          <div class="cell-agents">
            <template v-if="ds.bindings.length">
              <el-tooltip
                v-for="b in ds.bindings" :key="b.agentKey"
                :content="`数据权限：${b.scope === 'WRITE' ? '可读写' : '只读'}`"
                placement="top"
              >
                <span class="agent-chip" :class="{ write: b.scope === 'WRITE' }">
                  {{ AGENT_OPTIONS.find(a => a.key === b.agentKey)?.label ?? b.agentKey }}
                </span>
              </el-tooltip>
            </template>
            <span v-else class="no-binding">未绑定</span>
          </div>
          <div class="cell-actions">
            <el-button link type="primary" :loading="testingId === ds.id" @click="test(ds)">测试连接</el-button>
            <el-button link :loading="syncingId === ds.id" @click="sync(ds)">同步</el-button>
            <el-button link type="primary" @click="openEdit(ds)">编辑</el-button>
            <el-button link type="primary" @click="openDetail(ds)">详情</el-button>
            <el-button link type="danger" @click="remove(ds)">删除</el-button>
          </div>
        </div>
        <div v-if="!sources.length && !error" class="table-empty">
          <el-empty description="暂无数据源，点击右上角「新建数据源」开始登记" />
        </div>
      </div>
    </div>

    <PlatformBar :platform="platform" :running-count="runningCount" />

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑数据源' : '新建数据源'"
      width="640px"
    >
      <el-form label-width="92px" label-position="left">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：客户信息主库" maxlength="100" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio-button value="DATABASE">数据库</el-radio-button>
            <el-radio-button value="VECTOR">向量库</el-radio-button>
            <el-radio-button value="DOCUMENT">文档/文件</el-radio-button>
            <el-radio-button value="API">API 接口</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="子类型">
          <el-select v-model="form.engine" style="width: 220px">
            <el-option
              v-for="opt in ENGINE_OPTIONS[form.type]" :key="opt.value"
              :value="opt.value" :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" placeholder="这个数据源存什么、给谁用（可选）" />
        </el-form-item>
        <el-form-item label="连接配置">
          <div class="config-grid">
            <div v-for="field in configFields" :key="field.key" class="config-field">
              <span class="config-label">{{ field.label }}</span>
              <el-input
                v-model="form.config[field.key]"
                :type="field.secret ? 'password' : 'text'"
                :show-password="field.secret"
                :placeholder="field.secret && editingId ? '留空表示沿用原密码' : field.placeholder"
                autocomplete="new-password"
              />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="绑定 Agent">
          <div class="bind-row">
            <el-select v-model="form.agentKeys" multiple placeholder="选择使用该数据源的应用" style="flex: 1">
              <el-option v-for="a in AGENT_OPTIONS" :key="a.key" :value="a.key" :label="a.label" />
            </el-select>
            <el-select v-model="form.scope" style="width: 110px">
              <el-option value="READ" label="只读" />
              <el-option value="WRITE" label="可读写" />
            </el-select>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="数据源详情" size="460px">
      <template v-if="detailSource" #default>
        <div class="detail-section">
          <div class="detail-title">基本信息</div>
          <div class="detail-rows">
            <div class="detail-row"><span>名称</span><b>{{ detailSource.name }}</b></div>
            <div class="detail-row"><span>类型</span><b>{{ TYPE_LABELS[detailSource.type] }} · {{ ENGINE_LABELS[detailSource.engine] ?? detailSource.engine }}</b></div>
            <div class="detail-row"><span>状态</span>
              <b><span class="status-badge" :class="statusOf(detailSource.status).cls">
                <i class="dot" />{{ statusOf(detailSource.status).label }}
              </span></b>
            </div>
            <div class="detail-row"><span>最近同步</span><b>{{ fmtTime(detailSource.lastSyncAt) }}</b></div>
            <div class="detail-row"><span>登记人</span><b>{{ detailSource.owner }}</b></div>
            <div class="detail-row"><span>登记时间</span><b>{{ fmtTime(detailSource.createdAt) }}</b></div>
            <div v-if="detailSource.description" class="detail-row"><span>描述</span><b>{{ detailSource.description }}</b></div>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-title">连接配置</div>
          <div class="detail-rows">
            <div v-for="(value, key) in detailSource.config" :key="key" class="detail-row">
              <span>{{ CONFIG_LABELS[String(key)] ?? key }}</span><b class="mono">{{ value }}</b>
            </div>
          </div>
          <div v-if="detailSource.statusMsg" class="probe-msg">最近探活：{{ detailSource.statusMsg }}</div>
        </div>

        <div class="detail-section">
          <div class="detail-title">权限与使用关系</div>
          <div v-if="detailSource.bindings.length" class="binding-list">
            <div v-for="b in detailSource.bindings" :key="b.agentKey" class="binding-item">
              <span>{{ AGENT_OPTIONS.find(a => a.key === b.agentKey)?.label ?? b.agentKey }}</span>
              <span class="scope-tag" :class="{ write: b.scope === 'WRITE' }">{{ b.scope === 'WRITE' ? '可读写' : '只读' }}</span>
            </div>
          </div>
          <div v-else class="probe-msg">暂无 Agent 绑定此数据源。</div>
        </div>

        <div class="detail-section">
          <div class="detail-title">基础监控</div>
          <div class="metric-cards">
            <div v-for="m in metrics" :key="m.label" class="metric-card">
              <span class="metric-value">{{ m.value }}</span>
              <span class="metric-label">{{ m.label }}</span>
              <span class="metric-hint">{{ m.hint }}</span>
            </div>
          </div>
        </div>

        <div class="detail-actions">
          <el-button class="outline-btn" :loading="testingId === detailSource?.id" @click="detailSource && test(detailSource)">
            <el-icon class="l-icon"><Connection /></el-icon>测试连接
          </el-button>
          <el-button class="outline-btn" :loading="syncingId === detailSource?.id" @click="detailSource && sync(detailSource)">
            <el-icon class="l-icon"><Refresh /></el-icon>触发同步
          </el-button>
          <el-button class="outline-btn" @click="detailSource && openEdit(detailSource); detailVisible = false">
            <el-icon class="l-icon"><Edit /></el-icon>编辑
          </el-button>
          <el-button class="outline-btn danger" @click="detailSource && remove(detailSource); detailVisible = false">
            <el-icon class="l-icon"><Delete /></el-icon>删除
          </el-button>
          <el-button class="outline-btn" @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.ds-page {
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
  background: #f5f7fa;
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

/* 统计四卡 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}
.stat-card {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 15px 18px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #1f2d3d;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 4px;
}

/* 自绘表格（与全局列表风格一致） */
.ds-table {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.table-head,
.table-row {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) 150px minmax(0, 1fr) 120px minmax(0, 0.9fr) 250px;
  align-items: center;
  gap: 14px;
  padding-left: 20px;
  padding-right: 20px;
}
.table-head {
  height: 44px;
  font-size: 12.5px;
  color: #8a97a8;
  background: #fafbfc;
  border-bottom: 1px solid #eceff3;
}
.table-head .ta-right { text-align: right; }
.table-row {
  padding-top: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f2f4f7;
  font-size: 13px;
}
.table-row:last-child { border-bottom: 0; }
.table-row:hover { background: #fafcfe; }
.table-empty { padding: 24px 0; }

.cell-name { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ds-tile {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  background: linear-gradient(135deg, #e3eefb, #d2e4f7);
  color: #0b4f9e;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex-shrink: 0;
}
.ds-name-main { min-width: 0; }
.ds-name {
  font-size: 14.5px;
  font-weight: 700;
  color: #1f2d3d;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-name:hover { color: #0b4f9e; }
.ds-desc {
  font-size: 12px;
  color: #98a2b0;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-type { display: flex; flex-direction: column; gap: 5px; align-items: flex-start; }
.type-tag {
  font-size: 11.5px;
  font-weight: 600;
  color: #0b4f9e;
  background: #eaf2fb;
  border-radius: 4px;
  padding: 2px 8px;
}
.engine-tag { font-size: 11.5px; color: #8a97a8; }

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
.status-badge.error { color: #c0392b; background: #fdeeec; }
.status-badge.error .dot { background: #c0392b; }
.status-badge.syncing { color: #0b4f9e; background: #eaf2fb; }
.status-badge.syncing .dot { background: #0b4f9e; }
.status-badge.untested { color: #8a97a8; background: #f0f2f5; }
.status-badge.untested .dot { background: #b6bcc6; }
.status-msg {
  font-size: 11.5px;
  color: #98a2b0;
  margin-top: 4px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-time { font-size: 12.5px; color: #4a5a6d; font-variant-numeric: tabular-nums; }

.cell-agents { display: flex; flex-wrap: wrap; gap: 6px; }
.agent-chip {
  font-size: 11.5px;
  color: #4a5a6d;
  background: #f4f6f9;
  border: 1px solid #e2e7ee;
  border-radius: 999px;
  padding: 2px 10px;
  cursor: default;
}
.agent-chip.write { color: #6d28d9; background: #f3eefe; border-color: #e0d4f8; }
.no-binding { font-size: 12px; color: #b6bcc6; }

.cell-actions { display: flex; justify-content: flex-end; gap: 2px; flex-wrap: wrap; }

/* 表单：连接配置两列排布 */
.config-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 12px;
  width: 100%;
}
.config-field { display: flex; flex-direction: column; gap: 4px; }
.config-label { font-size: 12px; color: #8a97a8; }
.bind-row { display: flex; gap: 10px; width: 100%; }

/* 详情抽屉 */
.detail-section { margin-bottom: 22px; }
.detail-title {
  font-size: 13.5px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid #0b4f9e;
  line-height: 1.3;
}
.detail-rows { display: flex; flex-direction: column; gap: 8px; }
.detail-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 12.5px;
  color: #8a97a8;
}
.detail-row b { color: #1f2d3d; font-weight: 600; text-align: right; word-break: break-all; }
.detail-row .mono { font-variant-numeric: tabular-nums; }
.probe-msg {
  margin-top: 10px;
  font-size: 12px;
  color: #4a5a6d;
  background: #f7f9fb;
  border: 1px solid #eceff3;
  border-radius: 6px;
  padding: 8px 10px;
  line-height: 1.6;
}
.binding-list { display: flex; flex-direction: column; gap: 8px; }
.binding-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #1f2d3d;
  background: #f7f9fb;
  border: 1px solid #eceff3;
  border-radius: 6px;
  padding: 8px 12px;
}
.scope-tag {
  font-size: 11.5px;
  color: #0a8f3c;
  background: #eaf8f0;
  border-radius: 4px;
  padding: 2px 8px;
}
.scope-tag.write { color: #6d28d9; background: #f3eefe; }
.metric-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.metric-card {
  background: #f7f9fb;
  border: 1px solid #eceff3;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
}
.metric-value {
  font-size: 19px;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
}
.metric-label { font-size: 11.5px; color: #4a5a6d; margin-top: 3px; }
.metric-hint { font-size: 10.5px; color: #b6bcc6; margin-top: 2px; }

.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; }
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
.outline-btn .l-icon { margin-right: 4px; }

@media (max-width: 1100px) {
  .table-head, .table-row { grid-template-columns: minmax(0, 1.2fr) 130px minmax(0, 1fr) 110px 200px; }
  .cell-time { display: none; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
