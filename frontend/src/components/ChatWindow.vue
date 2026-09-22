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

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
const sidebarOpen = ref(true)
const sessions = ref<SidebarSession[]>([])
const loadingSessions = ref(false)
let memoryId = ''
let controller: AbortController | null = null

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
  sessions.value = []
  emit('newSession')
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
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

/** 新建会话：换 memoryId 并清空界面 */
function reset() {
  controller?.abort()
  memoryId = newMemoryId(props.agent)
  messages.value = [{ role: 'assistant', kind: 'text', content: props.welcome }]
  sessions.value = []
  scrollBottom()
}

function scrollBottom() {
  nextTick(() => listEl.value?.scrollTo({ top: listEl.value.scrollHeight }))
}
</script>

<template>
  <section class="chat">
    <div class="chat-head">
      <div class="chat-title">
        <button class="ghost" :disabled="loadingSessions" @click="toggleSidebar">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor">
            <path d="M4 6h16M4 12h16M4 18h16" stroke-width="2" stroke-linecap="round"/>
          </svg>
          侧边栏会话列表
        </button>
        <h2>{{ title }}</h2>
      </div>
      <button class="ghost" :disabled="streaming" @click="reset">新建会话</button>
    </div>

    <!-- 侧边栏会话列表 -->
    <aside v-if="sidebarOpen" class="sidebar" ref="sidebarEl">
      <div class="sidebar-header">
        <span>会话历史（{{ sessions.length }}）</span>
        <button class="ghost" @click="newSession">+ 新会话</button>
      </div>
      <div v-if="loadingSessions" class="loading">加载中…</div>
      <div v-else-if="sessions.length === 0" class="empty">暂无历史会话</div>
      <div
        v-else
        v-for="session in sessions"
        :key="session.memoryId"
        class="session-item"
        :class="{ active: session.memoryId === memoryId }"
        @click="switchSession(session.memoryId)"
      >
        <span class="session-label">{{ session.label }}</span>
        <span class="session-time">{{ session.lastTime ? formatTime(session.lastTime) : '' }}</span>
      </div>
    </aside>

    <div class="chat-body">
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

    <div class="chat-input">
      <input
        v-model="input"
        :placeholder="placeholder"
        :disabled="streaming"
        @keydown.enter="send"
      >
      <button :disabled="streaming" @click="send">
        {{ streaming ? '回复中…' : '发送' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.chat {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.chat-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-title h2 {
  margin: 0;
  font-size: 16px;
}

.sidebar {
  width: 260px;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.3s ease;
}

.sidebar-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.loading,
.empty {
  padding: 20px;
  text-align: center;
  color: var(--text-muted);
}

.session-item {
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
  transition: background 0.2s;
}

.session-item:hover {
  background: var(--bg);
}

.session-item.active {
  background: var(--bg);
  border-left: 3px solid var(--accent);
}

.session-label {
  display: block;
  font-size: 14px;
  color: var(--text);
}

.session-time {
  font-size: 12px;
  color: var(--text-muted);
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
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
  background: var(--bg);
  border: 1px solid var(--border);
}

/* 确认卡片：比普通消息更醒目 */
.msg.card {
  min-width: 260px;
  border: 1px solid var(--accent);
  background: var(--bg);
}

.card-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.card-row {
  font-size: 14px;
}

.card-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.card-actions .primary {
  background: var(--accent);
  color: #fff;
}

.card-status {
  margin-top: 10px;
  font-size: 14px;
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

.chat-input {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}

.chat-input input {
  flex: 1;
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
