<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMemoryId, newMemoryId, respondTransferConfirm, streamChat, listSessions, loadHistoryMessages, deleteSession, cacheMessages, readCachedMessages, cacheSessions, readCachedSessions, listIdentities, getIdentity, setIdentity } from '../api/chat'
import type { ConfirmRequestData, IdentityInfo } from '../api/chat'

export interface Suggestion {
  icon: string
  label: string
}

const props = withDefaults(defineProps<{
  /** 后端代理前缀：/bank | /knowledge | /interview */
  basePath: string
  /** Agent 标识（与后端 AgentSpec.name 一致） */
  agent: string
  title: string
  welcome: string
  placeholder?: string
  /** 助手名（欢迎屏大标题用，如「小银」） */
  botName?: string
  /** 欢迎屏副标题 */
  botTagline?: string
  /** 欢迎屏建议问题（点击直接发送） */
  suggestions?: Suggestion[]
  /** 启用多服务对象身份切换（银行助手专属：零售客户/内部员工/对公客户） */
  enableIdentity?: boolean
}>(), {
  placeholder: '输入消息，回车发送…',
})

const emit = defineEmits<{
  newSession: []
}>()

interface CardMsg extends ConfirmRequestData {
  status: 'pending' | 'processing' | 'confirmed' | 'cancelled'
}

interface Msg {
  role: 'user' | 'assistant'
  kind: 'text' | 'card'
  content: string
  card?: CardMsg
}

interface SidebarSession {
  memoryId: string
  label: string
  lastTime: string
  active: boolean
}

const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
const textareaEl = ref<HTMLTextAreaElement | null>(null)
const sidebarOpen = ref(true)
const sessions = ref<SidebarSession[]>([])
const loadingSessions = ref(false)
const messages = ref<Msg[]>([])
let memoryId = ''
let controller: AbortController | null = null
// 会话删除：menuFor = 展开"…"菜单的会话 ID；pendingDelete = 等待确认删除的会话
const menuFor = ref<string | null>(null)
const pendingDelete = ref<SidebarSession | null>(null)

// 多服务对象：可选身份列表与当前身份（仅 enableIdentity 时使用）
const identityList = ref<IdentityInfo[]>([])
const currentIdentityId = ref('')

/** 没有任何消息时显示欢迎屏（DeepSeek/Kimi 风格空状态） */
const showHero = computed(() => messages.value.length === 0 && !streaming.value)

const heroName = computed(() => props.botName ?? props.title)

/** 当前身份的元数据；未启用或未加载到时回退 props 默认值 */
const currentIdentity = computed(() =>
  identityList.value.find(i => i.id === currentIdentityId.value) ?? null)

/** 身份建议问题的展示图标（后端只给文本，图标按序循环） */
const HERO_ICONS = ['💰', '📋', '💸', '🧾']

const heroWelcome = computed(() =>
  currentIdentity.value?.welcome ?? props.botTagline ?? props.welcome)

const heroSuggestions = computed<Suggestion[]>(() => {
  const fromIdentity = currentIdentity.value?.suggestions
  if (fromIdentity?.length) {
    return fromIdentity.map((label, i) => ({ icon: HERO_ICONS[i % HERO_ICONS.length], label }))
  }
  return props.suggestions ?? []
})

onMounted(async () => {
  // 多服务对象模式：先定身份（localStorage 记住上次选择），会话与提示词都按身份隔离
  if (props.enableIdentity) {
    try {
      identityList.value = await listIdentities(props.basePath)
    } catch { /* 接口不可用时退化为无身份模式 */ }
    currentIdentityId.value = getIdentity(props.agent)
      ?? (identityList.value.length ? identityList.value[0].id : '')
    if (currentIdentityId.value) {
      setIdentity(props.agent, currentIdentityId.value)
    }
  }
  memoryId = getMemoryId(props.agent, currentIdentityId.value || undefined)
  // 先用本地缓存秒开（后端不在线时也有数据可看），再用 DB 最新数据覆盖
  const cached = readCachedMessages(props.agent, memoryId)
  if (cached.length) {
    messages.value = cached.map(m => ({ role: m.role as Msg['role'], kind: 'text', content: m.content }))
  }
  // 历史消息与会话列表并行加载，互不阻塞；历史拉取失败时保留缓存显示
  const [histRes] = await Promise.allSettled([
    loadHistoryMessages(props.basePath, props.agent, memoryId),
    loadSessions(),
  ])
  if (histRes.status === 'fulfilled' && histRes.value.length) {
    messages.value = histRes.value.map((h: any) => ({ role: h.role, kind: 'text', content: h.content }))
    cacheMessages(props.agent, memoryId, histRes.value)
  }
  scrollBottom()
})

onBeforeUnmount(() => controller?.abort())

/** 会话标题摘要（存 localStorage，DB 只存 memoryId） */
function loadLabelMap(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(`aiwb-labels-${props.agent}`) || '{}')
  } catch {
    return {}
  }
}

function saveLabel(label: string) {
  const m = loadLabelMap()
  m[memoryId] = label
  localStorage.setItem(`aiwb-labels-${props.agent}`, JSON.stringify(m))
}

/** 会话 ID 尾段（uuid）前 8 位做兜底名；直接截全串会把 "bank:身份:" 前缀带出来 */
function shortId(memoryId: string): string {
  const segs = memoryId.split(':')
  return segs[segs.length - 1].substring(0, 8)
}

async function loadSessions() {
  loadingSessions.value = true
  try {
    const labels = loadLabelMap()
    const apiSessions = await listSessions(props.basePath, props.agent, currentIdentityId.value || undefined)
    sessions.value = apiSessions
      .map((s: any) => ({
        ...s,
        // 命名优先级：用户手动命名 > 后端自动标题（首条消息） > ID 尾段兜底
        label: labels[s.memoryId] || s.title || `会话 ${shortId(s.memoryId)}`,
        // 后端返回 updatedAt，本地项用 lastTime，这里统一成 lastTime 供排序/显示
        lastTime: s.lastTime || s.updatedAt || '',
      }))
      .sort((a, b) => b.lastTime.localeCompare(a.lastTime))
    // 只在确实拿到数据时更新缓存快照，避免空响应把缓存清掉
    if (sessions.value.length) {
      cacheSessions(props.agent, sessions.value)
    }
  } catch (e) {
    console.error('加载会话列表失败:', e)
    // 后端不在线时用最近一次同步的快照兜底
    const cached = readCachedSessions(props.agent) as typeof sessions.value
    if (cached.length && sessions.value.length === 0) {
      sessions.value = cached
    }
  } finally {
    loadingSessions.value = false
  }
}

async function switchSession(sessionId: string) {
  if (loadingSessions.value || memoryId === sessionId) return
  memoryId = sessionId
  // 同步当前会话到 localStorage，保证刷新后仍停留在该会话
  localStorage.setItem(`aiwb-current-${props.agent}`, sessionId)
  // 缓存秒开，再拉 DB 最新
  const cached = readCachedMessages(props.agent, sessionId)
  if (cached.length) {
    messages.value = cached.map(m => ({ role: m.role as Msg['role'], kind: 'text', content: m.content }))
    scrollBottom()
  }
  // 拉取失败（后端不可用）时保留缓存显示
  try {
    const history = await loadHistoryMessages(props.basePath, props.agent, memoryId)
    if (history.length) {
      messages.value = history.map(h => ({ role: h.role, kind: 'text', content: h.content }))
      cacheMessages(props.agent, sessionId, history)
    } else if (!cached.length) {
      messages.value = []
    }
  } catch { /* keep cached messages */ }
  // 更新列表顺序（把选中的移到最前）
  const idx = sessions.value.findIndex(s => s.memoryId === sessionId)
  if (idx >= 0) {
    const selected = sessions.value.splice(idx, 1)[0]
    sessions.value.unshift(selected)
  }
  scrollBottom()
}

function newSession() {
  memoryId = newMemoryId(props.agent, currentIdentityId.value || undefined)
  messages.value = []
  // 立即在侧栏顶部出现"新对话"记录（此刻还未落库，不查 DB 以免把它冲掉）
  sessions.value = sessions.value.filter(s => s.label !== '新对话')
  sessions.value.unshift({ memoryId, label: '新对话', lastTime: new Date().toISOString(), active: true })
  scrollBottom()
}

/** 切换服务对象身份：中断进行中的回复，清空工作区，按新身份重建会话与列表 */
function switchIdentity(id: string) {
  if (id === currentIdentityId.value) return
  controller?.abort()
  controller = null
  streaming.value = false
  setIdentity(props.agent, id)
  currentIdentityId.value = id
  messages.value = []
  menuFor.value = null
  pendingDelete.value = null
  // 当前会话属于旧身份，getMemoryId 检测到前缀不匹配会自动开新会话
  memoryId = getMemoryId(props.agent, id)
  sessions.value = []
  loadSessions()
  scrollBottom()
}

/** 删除会话：DB 正本 + 本地缓存一起清；若删的是当前会话则自动切走 */
async function removeSession(sessionId: string) {
  pendingDelete.value = null
  try {
    await deleteSession(props.basePath, props.agent, sessionId)
  } catch { /* 后端不可用也继续清理本地，下次同步时以 DB 为准 */ }
  sessions.value = sessions.value.filter(s => s.memoryId !== sessionId)
  localStorage.removeItem(`aiwb-msgs-${props.agent}-${sessionId}`)
  // 清理会话名
  const labels = loadLabelMap()
  delete labels[sessionId]
  localStorage.setItem(`aiwb-labels-${props.agent}`, JSON.stringify(labels))
  cacheSessions(props.agent, sessions.value)
  if (memoryId === sessionId) {
    const next = sessions.value[0]
    if (next) {
      await switchSession(next.memoryId)
    } else {
      newSession()
    }
  }
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

/** 点击建议问题：直接发送 */
async function sendSuggestion(s: Suggestion) {
  if (streaming.value) return
  input.value = s.label
  await send()
}

async function send() {
  const message = input.value.trim()
  if (!message || streaming.value) return
  input.value = ''
  resetTextareaHeight()
  messages.value.push({ role: 'user', kind: 'text', content: message })

  // 首条消息后把"新对话"更新为消息摘要（并持久化，刷新后标题不丢）
  const item = sessions.value.find(s => s.memoryId === memoryId)
  if (item) {
    item.label = message.slice(0, 12)
    item.lastTime = new Date().toISOString()
    saveLabel(item.label)
  }

  // 从响应式数组里取代理对象，流式追加才会触发视图更新
  messages.value.push({ role: 'assistant', kind: 'text', content: '' })
  const reply = messages.value[messages.value.length - 1]

  streaming.value = true
  scrollBottom()
  controller = new AbortController()
  try {
    await streamChat({
      basePath: props.basePath,
      agent: props.agent,
      memoryId,
      message,
      signal: controller.signal,
      onDelta: (text) => {
        reply.content += text
        scrollBottom()
      },
      onError: (msg) => {
        reply.content += `\n[错误] ${msg}`
      },
    })
  } catch (e) {
    reply.content += `\n[连接失败] ${e instanceof Error ? e.message : String(e)}`
  } finally {
    streaming.value = false
    controller = null
    scrollBottom()
    // 本轮消息写入本地缓存（后端不在线时下次也能看到）
    cacheMessages(props.agent, memoryId, messages.value
      .filter(m => m.kind === 'text')
      .map(m => ({ role: m.role, content: m.content })))
    // 消息发出后重新加载会话列表（因为可能产生新的会话）
    loadSessions()
  }
}

/** 确认卡片操作：调后端确认接口，结果以普通消息回显 */
async function respondCard(card: CardMsg, action: 'confirm' | 'cancel') {
  if (card.status !== 'pending') return
  card.status = 'processing'
  try {
    const res = await respondTransferConfirm(props.basePath, memoryId, card.confirmId, action)
    card.status = action === 'confirm' ? 'confirmed' : 'cancelled'
    messages.value.push({ role: 'assistant', kind: 'text', content: res.message })
  } catch (e) {
    card.status = 'pending'
    messages.value.push({
      role: 'assistant',
      kind: 'text',
      content: `[确认失败] ${e instanceof Error ? e.message : String(e)}`,
    })
  }
  scrollBottom()
}

function scrollBottom() {
  nextTick(() => listEl.value?.scrollTo({ top: listEl.value.scrollHeight }))
}

/** textarea 自适应高度（1 行起，最多约 6 行） */
function autoResize() {
  const el = textareaEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

function resetTextareaHeight() {
  nextTick(() => {
    if (textareaEl.value) textareaEl.value.style.height = 'auto'
  })
}

/** 格式化时间（只显示 MM-DD HH:mm） */
function formatTime(timestamp: string): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
}
</script>

<template>
  <div class="chat-container">
    <!-- 侧边栏：会话列表 -->
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="sidebar-inner">
        <!-- 多服务对象：身份选择器（零售客户/内部员工/对公客户） -->
        <div v-if="enableIdentity && identityList.length" class="identity-bar">
          <span class="identity-label">服务对象</span>
          <select
            class="identity-select"
            :value="currentIdentityId"
            @change="switchIdentity(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="i in identityList" :key="i.id" :value="i.id">{{ i.displayName }}</option>
          </select>
        </div>

        <button class="new-chat" @click="newSession">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <path d="M12 5v14M5 12h14" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          开启新对话
        </button>

        <div class="sidebar-caption">历史会话</div>

        <div v-if="loadingSessions" class="loading">加载中…</div>
        <div v-else-if="sessions.length === 0" class="empty">
          <p>暂无历史会话</p>
          <p class="hint">开始新对话后，记录会显示在这里</p>
        </div>
        <template v-else>
          <div
            v-for="session in sessions"
            :key="session.memoryId"
            class="session-item"
            :class="{ active: session.memoryId === memoryId, 'menu-open': menuFor === session.memoryId }"
            @click="switchSession(session.memoryId)"
          >
            <span class="session-label">{{ session.label }}</span>
            <span class="session-time">{{ formatTime(session.lastTime) }}</span>
            <button
              class="session-more"
              title="更多操作"
              @click.stop="menuFor = menuFor === session.memoryId ? null : session.memoryId"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="5" cy="12" r="2" />
                <circle cx="12" cy="12" r="2" />
                <circle cx="19" cy="12" r="2" />
              </svg>
            </button>
            <!-- DeepSeek 式下拉菜单 -->
            <div v-if="menuFor === session.memoryId" class="session-menu" @click.stop>
              <button class="session-menu-item danger" @click="pendingDelete = session; menuFor = null">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                删除
              </button>
            </div>
          </div>
        </template>
      </div>

      <div class="sidebar-footer">
        <span class="footer-logo">🏦</span>
        <span>AI Workbench · 银行助手</span>
      </div>
      <!-- 菜单展开时的全屏透明遮罩：点击任意处关闭 -->
      <div v-if="menuFor" class="menu-mask" @click="menuFor = null"></div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
      <!-- 顶栏 -->
      <header class="chat-topbar">
        <button class="icon-btn" :disabled="loadingSessions" @click="toggleSidebar" title="收起/展开侧栏">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M9 4v16" />
          </svg>
        </button>
        <h2 class="chat-title">{{ title }}</h2>
      </header>

      <!-- 消息滚动区 -->
      <div class="chat-scroll" ref="listEl">
        <!-- 欢迎屏：无消息时居中展示 -->
        <div v-if="showHero" class="hero">
          <div class="hero-logo">🏦</div>
          <h1 class="hero-title">你好，我是{{ heroName }}</h1>
          <p class="hero-tagline">{{ heroWelcome }}</p>
          <div v-if="heroSuggestions.length" class="suggestions">
            <button
              v-for="s in heroSuggestions"
              :key="s.label"
              class="suggestion"
              @click="sendSuggestion(s)"
            >
              <span class="s-icon">{{ s.icon }}</span>
              <span>{{ s.label }}</span>
            </button>
          </div>
        </div>

        <!-- 消息流 -->
        <div v-else class="msg-list">
          <div
            v-for="(m, i) in messages"
            :key="i"
            class="msg-row"
            :class="[m.role, { card: m.kind === 'card' }]"
          >
            <div class="avatar" :class="m.role">
              <template v-if="m.role === 'assistant'">🏦</template>
              <template v-else>我</template>
            </div>
            <div class="bubble">
              <template v-if="m.kind === 'card' && m.card">
                <div class="card-head">
                  <span class="card-icon">💸</span>
                  <span>待确认转账</span>
                </div>
                <div class="card-amount">¥{{ m.card.amount.toFixed(2) }}</div>
                <div class="card-row"><span class="k">收款人</span><span>{{ m.card.toOwner }}（{{ m.card.toAccount }}）</span></div>
                <div class="card-row"><span class="k">付款账户</span><span>{{ m.card.fromAccount }}</span></div>
                <div v-if="m.card.reason" class="card-row"><span class="k">附言</span><span>{{ m.card.reason }}</span></div>
                <div v-if="m.card.status === 'pending'" class="card-actions">
                  <button class="primary" @click="respondCard(m.card, 'confirm')">确认转账</button>
                  <button class="ghost" @click="respondCard(m.card, 'cancel')">取消</button>
                </div>
                <div v-else-if="m.card.status === 'processing'" class="card-status">处理中…</div>
                <div v-else-if="m.card.status === 'confirmed'" class="card-status ok">✅ 已确认</div>
                <div v-else class="card-status">🚫 已取消</div>
              </template>
              <template v-else>
                {{ m.content }}<template
                  v-if="streaming && m.role === 'assistant' && i === messages.length - 1"
                ><span v-if="!m.content" class="thinking">思考中…</span><span
                    v-else
                    class="caret"
                  >▍</span></template>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="composer-wrap">
        <div class="composer">
          <textarea
            ref="textareaEl"
            v-model="input"
            rows="1"
            :placeholder="placeholder"
            :disabled="streaming"
            @keydown.enter.exact.prevent="send"
            @input="autoResize"
          ></textarea>
          <button class="send-btn" :disabled="streaming || !input.trim()" @click="send" title="发送">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 19V6" />
              <path d="M5.5 12.5 12 6l6.5 6.5" />
            </svg>
          </button>
        </div>
        <p class="disclaimer">内容由 AI 生成，仅供参考 · 转账操作需二次确认</p>
      </div>
    </main>

    <!-- 删除会话二次确认弹窗 -->
    <div v-if="pendingDelete" class="confirm-mask" @click.self="pendingDelete = null">
      <div class="confirm-dialog">
        <h3 class="confirm-title">删除会话</h3>
        <p class="confirm-text">确定删除「{{ pendingDelete.label }}」吗？删除后聊天记录将不可恢复。</p>
        <div class="confirm-actions">
          <button class="confirm-btn ghost" @click="pendingDelete = null">取消</button>
          <button class="confirm-btn danger" @click="removeSession(pendingDelete.memoryId)">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-container {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 0;
  flex-shrink: 0;
  background: #f7f8fa;
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  overflow: hidden;
}

.sidebar.open {
  width: 264px;
}

.sidebar-inner {
  width: 264px;
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px 12px 8px;
  min-height: 0;
}

.new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 11px 0;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

.new-chat:hover {
  background: #3d5ce0;
}

/* 多服务对象：身份选择器（侧栏顶部） */
.identity-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 0 2px;
}

.identity-label {
  font-size: 12px;
  color: var(--text-dim);
  white-space: nowrap;
}

.identity-select {
  flex: 1;
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}

.identity-select:hover {
  border-color: var(--accent);
}

.identity-select:focus {
  outline: none;
  border-color: var(--accent);
}

.sidebar-caption {
  margin: 20px 6px 8px;
  font-size: 12px;
  color: var(--text-dim);
}

.loading {
  padding: 24px 12px;
  text-align: center;
  color: var(--text-dim);
  font-size: 13px;
}

.empty {
  padding: 28px 12px;
  text-align: center;
  color: var(--text-dim);
  font-size: 13px;
}

.empty p {
  margin: 2px 0;
}

.empty .hint {
  font-size: 12px;
  opacity: 0.75;
}

.session-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}

.session-item:hover {
  background: #eceef2;
}

.session-item.active {
  background: var(--accent-weak);
}

.session-item.active .session-label {
  color: var(--accent);
  font-weight: 600;
}

.session-label {
  flex: 1;
  font-size: 13px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-time {
  font-size: 11px;
  color: var(--text-dim);
  white-space: nowrap;
}

/* "…" 更多按钮：hover 或菜单展开时显现，当前选中会话常显 */
.session-more {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-dim);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}

.session-item:hover .session-more,
.session-item.menu-open .session-more,
.session-item.active .session-more {
  opacity: 1;
}

.session-more:hover {
  background: rgba(0, 0, 0, 0.07);
  color: var(--text);
}

/* DeepSeek 式会话下拉菜单 */
.session-menu {
  position: absolute;
  right: 8px;
  top: calc(100% + 2px);
  z-index: 12;
  min-width: 96px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  padding: 4px;
}

.session-menu-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
}

.session-menu-item.danger {
  color: #e5484d;
}

.session-menu-item.danger:hover {
  background: #fef1f1;
}

/* 菜单展开时的全屏透明遮罩：点击任意处关闭菜单 */
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 10;
}

/* ===== 删除会话确认弹窗 ===== */
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
}

.confirm-dialog {
  width: 320px;
  background: #fff;
  border-radius: 14px;
  padding: 20px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2);
}

.confirm-title {
  margin: 0 0 8px;
  font-size: 15px;
  color: var(--text);
}

.confirm-text {
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-dim);
  word-break: break-all;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.confirm-btn {
  padding: 7px 16px;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}

.confirm-btn.ghost {
  background: #f2f3f5;
  color: var(--text);
}

.confirm-btn.ghost:hover {
  background: #e8e9eb;
}

.confirm-btn.danger {
  background: #e5484d;
  color: #fff;
}

.confirm-btn.danger:hover {
  background: #dc3d43;
}

.sidebar-footer {
  width: 264px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 18px;
  font-size: 12px;
  color: var(--text-dim);
  border-top: 1px solid var(--border);
}

.footer-logo {
  font-size: 14px;
}

/* ===== 主区域 ===== */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
}

.chat-topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid #f0f1f4;
  background: #fff;
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-dim);
  cursor: pointer;
  transition: all 0.15s;
}

.icon-btn:hover:not(:disabled) {
  background: #f0f1f4;
  color: var(--text);
}

.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.chat-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== 消息滚动区 ===== */
.chat-scroll {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

/* 欢迎屏 */
.hero {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
}

.hero-logo {
  width: 68px;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  border-radius: 22px;
  background: linear-gradient(135deg, var(--accent), #7c9bff);
  box-shadow: 0 10px 28px rgba(79, 110, 242, 0.35);
  margin-bottom: 22px;
}

.hero-title {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 700;
  color: var(--text);
  letter-spacing: 0.5px;
}

.hero-tagline {
  margin: 0 0 34px;
  font-size: 14px;
  color: var(--text-dim);
  max-width: 460px;
  line-height: 1.7;
}

.suggestions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
  max-width: 640px;
}

.suggestion {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  font-size: 13.5px;
  color: var(--text);
  cursor: pointer;
  text-align: left;
  transition: all 0.18s;
  white-space: nowrap;
  overflow: hidden;
}

.suggestion span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
}

.suggestion:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 14px rgba(79, 110, 242, 0.12);
  transform: translateY(-1px);
}

.s-icon {
  font-size: 17px;
}

/* 消息流 */
.msg-list {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 20px 12px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.msg-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 15px;
}

.avatar.assistant {
  background: linear-gradient(135deg, var(--accent), #7c9bff);
  color: #fff;
  box-shadow: 0 3px 10px rgba(79, 110, 242, 0.25);
}

.avatar.user {
  background: #e8ebf2;
  color: var(--text-dim);
  font-size: 12px;
  font-weight: 600;
}

.bubble {
  max-width: 78%;
  padding: 11px 15px;
  border-radius: 16px;
  line-height: 1.7;
  font-size: 14.5px;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-row.user .bubble {
  background: var(--accent);
  color: #fff;
  border-top-right-radius: 5px;
}

.msg-row.assistant .bubble {
  flex: 1;
  min-width: 0;
  max-width: none;
  background: transparent;
  color: var(--text);
  padding: 2px 4px;
  border-radius: 0;
}

.msg-row.assistant .caret {
  color: var(--accent);
}

/* 确认卡片：AI 全宽内容区里的浮卡 */
.msg-row.card .bubble {
  flex: 0 1 auto;
  width: 400px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #dbe3f8;
  box-shadow: 0 6px 20px rgba(79, 110, 242, 0.1);
  border-radius: 16px;
  padding: 16px 18px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text);
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--border);
}

.card-icon {
  font-size: 16px;
}

.card-amount {
  font-size: 24px;
  font-weight: 700;
  color: var(--accent);
  margin: 12px 0 10px;
  letter-spacing: 0.3px;
}

.card-row {
  display: flex;
  gap: 14px;
  font-size: 13.5px;
  margin: 6px 0;
}

.card-row .k {
  color: var(--text-dim);
  flex-shrink: 0;
  width: 56px;
}

.card-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.card-actions .primary {
  background: var(--accent);
  color: #fff;
  border: none;
  padding: 9px 20px;
  border-radius: 9px;
  font-size: 13.5px;
  cursor: pointer;
  transition: background 0.2s;
}

.card-actions .primary:hover {
  background: #3d5ce0;
}

.card-actions .ghost {
  background: #fff;
  border: 1px solid var(--border);
  color: var(--text-dim);
  padding: 9px 20px;
  border-radius: 9px;
  font-size: 13.5px;
  cursor: pointer;
  transition: all 0.2s;
}

.card-actions .ghost:hover {
  border-color: var(--text-dim);
  color: var(--text);
}

.card-status {
  margin-top: 14px;
  font-size: 13px;
  padding: 9px 13px;
  border-radius: 8px;
  background: #f4f6f9;
}

.card-status.ok {
  color: #16a34a;
  background: #f0fdf4;
}

.caret {
  animation: blink 0.9s steps(1) infinite;
  color: var(--accent);
}

/* GLM 是推理模型：思考阶段没有 delta，先给占位避免看起来像卡死 */
.thinking {
  color: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* ===== 输入区 ===== */
.composer-wrap {
  padding: 12px 20px 14px;
  background: linear-gradient(to top, #fff 70%, rgba(255, 255, 255, 0));
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  max-width: 820px;
  margin: 0 auto;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 24px;
  padding: 9px 9px 9px 18px;
  box-shadow: 0 4px 24px rgba(31, 35, 41, 0.06);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.composer:focus-within {
  border-color: var(--accent);
  box-shadow: 0 4px 28px rgba(79, 110, 242, 0.16);
}

.composer textarea {
  flex: 1;
  border: none;
  background: transparent;
  font: inherit;
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--text);
  padding: 7px 0;
  resize: none;
  outline: none;
  max-height: 160px;
}

.composer textarea::placeholder {
  color: #b3b9c4;
}

.composer textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* DeepSeek 风格：纯色圆形按钮，无渐变无重阴影，禁用为灰底白箭头 */
.send-btn {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  transition: background 0.2s, transform 0.15s;
}

/* 显式定尺寸：防止 svg 作为 flex 子项被压缩到 0 宽 */
.send-btn svg {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  display: block;
}

.send-btn:hover:not(:disabled) {
  background: #4359d8;
}

.send-btn:active:not(:disabled) {
  transform: scale(0.94);
}

.send-btn:disabled {
  background: #dde2ec;
  color: rgba(255, 255, 255, 0.9);
  cursor: not-allowed;
}

.disclaimer {
  max-width: 820px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 11px;
  color: #b3b9c4;
}
</style>
