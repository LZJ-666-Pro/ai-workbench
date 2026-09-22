<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMemoryId, newMemoryId, respondTransferConfirm, streamChat, listSessions, loadHistoryMessages } from '../api/chat'
import type { ConfirmRequestData } from '../api/chat'

const props = withDefaults(defineProps<{
  /** 后端代理前缀：/bank | /knowledge | /interview */
  basePath: string
  /** Agent 标识（与后端 AgentSpec.name 一致） */
  agent: string
  title: string
  welcome: string
  placeholder?: string
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
const sidebarOpen = ref(true)
const sidebarCollapsed = ref(false)
const sessions = ref<SidebarSession[]>([])
const loadingSessions = ref(false)
const messages = ref<Msg[]>([])
let memoryId = ''
let controller: AbortController | null = null

onMounted(async () => {
  memoryId = getMemoryId(props.agent)
  // 欢迎语置顶，随后按时间序恢复该会话的历史消息（刷新/重进页面不丢聊天记录）
  messages.value.push({ role: 'assistant', kind: 'text', content: props.welcome })
  const history = await loadHistoryMessages(props.basePath, props.agent, memoryId)
  history.forEach((h: any) => messages.value.push({ role: h.role, kind: 'text', content: h.content }))
  // 加载会话列表
  await loadSessions()
  scrollBottom()
})

onBeforeUnmount(() => controller?.abort())

async function loadSessions() {
  loadingSessions.value = true
  try {
    const apiSessions = await listSessions(props.basePath, props.agent)
    // 过滤掉空白会话
    sessions.value = apiSessions
      .map((s: any) => ({
        ...s,
        label: `会话 ${s.memoryId.substring(0, 8)}`
      }))
      .sort((a, b) => b.lastTime.localeCompare(a.lastTime))
  } catch (e) {
    console.error('加载会话列表失败:', e)
  } finally {
    loadingSessions.value = false
  }
}

async function switchSession(sessionId: string) {
  if (loadingSessions.value || memoryId === sessionId) return
  memoryId = sessionId
  const history = await loadHistoryMessages(props.basePath, props.agent, memoryId)
  // 保留欢迎语，追加历史消息
  const welcome = messages.value.find(m => m.kind === 'text' && m.role === 'assistant' && m.content === props.welcome)
  messages.value = [welcome || { role: 'assistant', kind: 'text', content: props.welcome }]
  history.forEach(h => messages.value.push({ role: h.role, kind: 'text', content: h.content }))
  // 更新列表顺序（把选中的移到最前）
  const idx = sessions.value.findIndex(s => s.memoryId === sessionId)
  if (idx >= 0) {
    const selected = sessions.value.splice(idx, 1)[0]
    sessions.value.unshift(selected)
  }
  scrollBottom()
}

function newSession() {
  memoryId = newMemoryId(props.agent)
  messages.value = [{ role: 'assistant', kind: 'text', content: props.welcome }]
  // 清空会话列表，显示"暂无历史会话"
  sessions.value = []
  // 收起侧边栏（如果是新建会话）
  sidebarOpen.value = false
  scrollBottom()
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
  sidebarCollapsed.value = !sidebarOpen.value
}

async function send() {
  const message = input.value.trim()
  if (!message || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', kind: 'text', content: message })

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
    <!-- 侧边栏会话列表 -->
    <aside v-if="sidebarOpen" class="sidebar" ref="sidebarEl">
      <!-- 侧边栏头部 -->
      <div class="sidebar-header">
        <div class="sidebar-title">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>会话历史（{{ sessions.length }}）</span>
        </div>
        <button class="new-chat-btn" @click="newSession" title="新建会话">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <path d="M12 5v14M5 12h14" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>

      <!-- 会话列表 -->
      <div v-if="loadingSessions" class="loading">加载中…</div>
      <div v-else-if="sessions.length === 0" class="empty">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
        </svg>
        <p>暂无历史会话</p>
        <p class="hint">开始新对话后，历史记录会显示在这里</p>
      </div>
      <div
        v-else
        v-for="session in sessions"
        :key="session.memoryId"
        class="session-item"
        :class="{ active: session.memoryId === memoryId }"
        @click="switchSession(session.memoryId)"
      >
        <div class="session-info">
          <span class="session-label">{{ session.label }}</span>
          <span class="session-time">{{ formatTime(session.lastTime) }}</span>
        </div>
      </div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main" :class="{ expanded: sidebarCollapsed && sidebarOpen }">
      <!-- 顶部工具栏 -->
      <div class="chat-toolbar">
        <button class="icon-btn" :disabled="loadingSessions" @click="toggleSidebar" title="切换侧边栏">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 6h16M4 12h16M4 18h16" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <h2 class="chat-title">{{ title }}</h2>
      </div>

      <!-- 聊天消息列表 -->
      <div class="chat-body" ref="listEl">
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="[m.role, { card: m.kind === 'card' }]"
        >
          <template v-if="m.kind === 'card' && m.card">
            <div class="card-title">💸 待确认转账</div>
            <div class="card-row">收款：{{ m.card.toOwner }}（{{ m.card.toAccount }}）</div>
            <div class="card-row">金额：<b>¥{{ m.card.amount.toFixed(2) }}</b></div>
            <div v-if="m.card.reason" class="card-row">附言：{{ m.card.reason }}</div>
            <div class="card-row">付款：{{ m.card.fromAccount }}</div>
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

      <!-- 输入区域 -->
      <div class="chat-input">
        <div class="input-wrapper">
          <input
            v-model="input"
            :placeholder="placeholder"
            :disabled="streaming"
            @keydown.enter="send"
          >
          <button :disabled="streaming" @click="send">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"></line>
              <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
            </svg>
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat-container {
  display: flex;
  height: 100%;
  background: var(--bg);
}

/* 侧边栏 */
.sidebar {
  width: 280px;
  background: var(--bg);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
}

.sidebar-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sidebar-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.sidebar-title svg {
  color: var(--accent);
}

.new-chat-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--text);
  cursor: pointer;
  transition: all 0.2s;
}

.new-chat-btn:hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.new-chat-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading {
  padding: 40px 20px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--text-muted);
}

.empty svg {
  color: var(--border);
  margin-bottom: 12px;
}

.empty p {
  margin: 4px 0;
  font-size: 13px;
}

.empty .hint {
  font-size: 12px;
  opacity: 0.7;
}

.session-item {
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
  transition: all 0.2s;
}

.session-item:hover {
  background: var(--card);
}

.session-item.active {
  background: var(--accent);
}

.session-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.session-label {
  flex: 1;
  font-size: 13px;
  color: var(--text);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-time {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}

/* 主聊天区域 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  transition: flex 0.3s ease;
}

.chat-main.expanded {
  flex: 1;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--card);
}

.icon-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--text);
  cursor: pointer;
  transition: all 0.2s;
}

.icon-btn:hover:not(:disabled) {
  background: var(--bg);
}

.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.chat-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 聊天消息列表 */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.msg {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.user {
  align-self: flex-end;
  background: var(--accent);
  color: #fff;
}

.msg.assistant {
  align-self: flex-start;
  background: var(--card);
  border: 1px solid var(--border);
}

/* 确认卡片：比普通消息更醒目 */
.msg.card {
  min-width: 260px;
  border: 1px solid var(--accent);
  background: var(--card);
}

.card-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--text);
}

.card-row {
  font-size: 14px;
  color: var(--text);
  margin: 4px 0;
}

.card-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.card-actions .primary {
  background: var(--accent);
  color: #fff;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.card-actions .primary:hover {
  opacity: 0.9;
}

.card-actions .ghost {
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text);
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.card-actions .ghost:hover {
  border-color: var(--text-muted);
}

.card-status {
  margin-top: 12px;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--bg);
}

.card-status.ok {
  color: var(--accent);
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

/* 输入区域 */
.chat-input {
  padding: 20px;
  border-top: 1px solid var(--border);
  background: var(--card);
}

.input-wrapper {
  display: flex;
  gap: 12px;
  max-width: 800px;
  margin: 0 auto;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 8px;
  transition: border-color 0.2s;
}

.input-wrapper:focus-within {
  border-color: var(--accent);
}

.input-wrapper input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 14px;
  color: var(--text);
  padding: 8px 12px;
}

.input-wrapper input:focus {
  outline: none;
}

.input-wrapper input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.input-wrapper button {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  transition: opacity 0.2s;
}

.input-wrapper button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-wrapper button:hover:not(:disabled) {
  opacity: 0.9;
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
</style>
