<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
/** 三个助手页与管理后台是全屏工作区，不走 .main 的限宽布局 */
const isChatRoute = computed(() =>
  ['/bank', '/knowledge', '/interview', '/admin'].some(p => route.path.startsWith(p)),
)

/** 顶栏导航：银行助手页只保留业务相关入口，其余页面显示全部 */
const allLinks = [
  { to: '/bank', label: '🏦 银行助手' },
  { to: '/knowledge', label: '📚 知识库' },
  { to: '/interview', label: '🎤 面试模拟' },
  { to: '/admin', label: '📊 管理后台' },
]
const navLinks = computed(() =>
  route.path.startsWith('/bank')
    ? allLinks.filter(l => l.to === '/bank' || l.to === '/admin')
    : allLinks,
)
</script>

<template>
  <header class="topbar">
    <RouterLink to="/" class="brand">🧠 智汇工作台</RouterLink>
    <nav>
      <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
    </nav>
  </header>
  <main class="main" :class="{ flush: isChatRoute }">
    <RouterView />
  </main>
</template>
