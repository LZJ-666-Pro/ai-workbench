<script setup lang="ts">
</script>

<template>
  <header class="topbar">
    <RouterLink to="/" class="brand">🛠️ ai-workbench</RouterLink>
    <nav>
      <RouterLink to="/bank">🏦 银行助手</RouterLink>
      <RouterLink to="/knowledge">📚 知识库</RouterLink>
      <RouterLink to="/interview">🎤 面试模拟</RouterLink>
    </nav>
  </header>
  <main class="main">
    <RouterView />
  </main>
</template>
