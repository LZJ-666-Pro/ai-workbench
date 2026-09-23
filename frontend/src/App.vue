<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
/** 三个助手页是全屏聊天工作区，不走 .main 的限宽布局 */
const isChatRoute = computed(() =>
  ['/bank', '/knowledge', '/interview'].some(p => route.path.startsWith(p)),
)
</script>

<template>
  <header class="topbar">
    <RouterLink to="/" class="brand">🧠 智汇工作台</RouterLink>
    <nav>
      <RouterLink to="/bank">🏦 银行助手</RouterLink>
      <RouterLink to="/knowledge">📚 知识库</RouterLink>
      <RouterLink to="/interview">🎤 面试模拟</RouterLink>
    </nav>
  </header>
  <main class="main" :class="{ flush: isChatRoute }">
    <RouterView />
  </main>
</template>
