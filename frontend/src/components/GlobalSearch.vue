<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { CATEGORY_LABELS, searchPlatform, type SearchResult } from '../api/platform'

const router = useRouter()

const keyword = ref('')
const open = ref(false)
const loading = ref(false)
const result = ref<SearchResult | null>(null)
const active = ref(0)
const root = ref<HTMLElement | null>(null)

let debounceTimer: number | undefined
/** 请求序号：只接受最后一次请求的结果，避免慢的旧响应覆盖快的新响应 */
let seq = 0

interface Item {
  key: string
  group: '应用' | '日志' | '接口'
  title: string
  subtitle: string
  to: string
  badge?: string
}

/** 把三组结果展平成一个列表，方向键才能一路穿行；分组名只做展示 */
const items = computed<Item[]>(() => {
  const r = result.value
  if (!r) return []
  return [
    ...r.apps.map(a => ({
      key: `app-${a.key}`, group: '应用' as const, title: a.name, subtitle: a.type, to: a.to,
    })),
    ...r.logs.map(l => ({
      key: `log-${l.id}`, group: '日志' as const,
      title: `${l.action} · ${CATEGORY_LABELS[l.category] ?? l.category}`,
      subtitle: l.detail,
      // 有 traceId 就按 traceId 跳：那是精确定位这一次请求；否则退回按动作关键字搜
      to: `/logs?keyword=${encodeURIComponent(l.traceId ?? l.action)}`,
      badge: l.result,
    })),
    ...r.endpoints.map(e => ({
      key: `ep-${e.method}-${e.path}`, group: '接口' as const,
      title: e.path, subtitle: '已注册的后端接口', to: '/developers', badge: e.method,
    })),
  ]
})

const isEmpty = computed(() => !loading.value && keyword.value.trim() !== '' && items.value.length === 0)

watch(keyword, value => {
  window.clearTimeout(debounceTimer)
  const q = value.trim()
  if (!q) {
    result.value = null
    loading.value = false
    return
  }
  loading.value = true
  open.value = true
  // 防抖：输入「转账」会触发三次请求，每次都打后端没有意义
  debounceTimer = window.setTimeout(async () => {
    const mine = ++seq
    try {
      const r = await searchPlatform(q)
      if (mine !== seq) return
      result.value = r
      active.value = 0
    } catch {
      if (mine === seq) result.value = null
    } finally {
      if (mine === seq) loading.value = false
    }
  }, 250)
})

function go(item: Item) {
  open.value = false
  keyword.value = ''
  result.value = null
  router.push(item.to)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    open.value = false
    return
  }
  if (!items.value.length) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = (active.value + 1) % items.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = (active.value - 1 + items.value.length) % items.value.length
  } else if (e.key === 'Enter') {
    e.preventDefault()
    go(items.value[active.value])
  }
}

function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  window.clearTimeout(debounceTimer)
})
</script>

<template>
  <div ref="root" class="gsearch">
    <el-input
      v-model="keyword"
      placeholder="搜索应用、日志、接口..."
      :prefix-icon="Search"
      clearable
      class="gsearch-input"
      @focus="open = keyword.trim() !== ''"
      @keydown="onKeydown"
    />
    <div v-if="open && (items.length || isEmpty || loading)" class="gsearch-panel">
      <div v-if="loading && !items.length" class="gs-hint">搜索中…</div>
      <div v-else-if="isEmpty" class="gs-hint">没有匹配的「{{ keyword }}」</div>
      <template v-else>
        <template v-for="(item, i) in items" :key="item.key">
          <!-- 分组标题：只在切换分组时出现一行，避免每条都重复标注 -->
          <div v-if="i === 0 || items[i - 1].group !== item.group" class="gs-group">{{ item.group }}</div>
          <div
            class="gs-item"
            :class="{ active: i === active }"
            @mouseenter="active = i"
            @click="go(item)"
          >
            <span class="gs-title">{{ item.title }}</span>
            <span v-if="item.badge" class="gs-badge">{{ item.badge }}</span>
            <span class="gs-sub">{{ item.subtitle }}</span>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.gsearch {
  position: relative;
  width: 260px;
}
.gsearch-input :deep(.el-input__wrapper) {
  border-radius: 8px;
}
.gsearch-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  max-height: 60vh;
  overflow: auto;
  background: #fff;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  box-shadow: 0 12px 30px rgba(15, 40, 80, 0.14);
  padding: 6px;
  z-index: 40;
}
.gs-hint {
  padding: 12px;
  font-size: 12.5px;
  color: #98a2b0;
  text-align: center;
}
.gs-group {
  font-size: 11px;
  font-weight: 700;
  color: #98a2b0;
  letter-spacing: 0.5px;
  padding: 8px 10px 4px;
}
.gs-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 8px;
  padding: 8px 10px;
  border-radius: 7px;
  cursor: pointer;
}
.gs-item.active {
  background: #eef4fb;
}
.gs-title {
  font-size: 13px;
  color: #1f2d3d;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gs-badge {
  font-size: 10.5px;
  font-weight: 700;
  color: #5a6b80;
  background: #f0f2f6;
  border-radius: 4px;
  padding: 1px 6px;
  align-self: start;
}
.gs-sub {
  grid-column: 1 / -1;
  font-size: 11.5px;
  color: #98a2b0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
