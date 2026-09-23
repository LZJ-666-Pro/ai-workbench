<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'

const route = useRoute()
/** 三个助手页与管理后台是全屏工作区，不走 .main 的限宽布局 */
const isChatRoute = computed(() =>
  ['/bank', '/knowledge', '/interview', '/admin'].some(p => route.path.startsWith(p)),
)

const isAdminRoute = computed(() => route.path.startsWith('/admin'))

/** 首页与开发者页需要更宽的容器（1280px），体现平台容量感 */
const isWideRoute = computed(() => ['/', '/developers'].includes(route.path))

/** 顶栏导航：首页/开发者页是平台级导航（具体应用由应用中心卡片承载）；银行助手页保留其管理后台；管理后台页由侧栏菜单承担导航 */
const platformLinks = [
  { to: '/', label: '应用中心' },
  { to: '/developers', label: '开发者' },
]
const allLinks = [
  { to: '/bank', label: '🏦 银行助手' },
  { to: '/knowledge', label: '📚 知识库' },
  { to: '/interview', label: '🎤 面试模拟' },
  { to: '/admin', label: '📊 管理后台' },
]
const navLinks = computed(() => {
  if (isAdminRoute.value) return []
  if (route.path.startsWith('/bank')) {
    return allLinks.filter(l => l.to === '/bank' || l.to === '/admin')
  }
  if (isWideRoute.value) {
    return platformLinks
  }
  return allLinks
})
</script>

<template>
  <header class="topbar">
    <RouterLink to="/" class="brand">🧠 智汇工作台</RouterLink>
    <nav v-if="navLinks.length">
      <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
    </nav>
    <div v-if="route.path === '/'" class="home-extras">
      <el-popover placement="bottom-end" :width="300" trigger="click">
        <template #reference>
          <el-badge :value="3" :offset="[-4, 4]" class="bell-wrap">
            <el-icon class="bell"><Bell /></el-icon>
          </el-badge>
        </template>
        <div class="notif-title">通知中心</div>
        <div class="notif-item">
          <span class="notif-dot warn" />转账审批：星辰科技 ¥380,000 待审批
        </div>
        <div class="notif-item">
          <span class="notif-dot" />风控黑名单更新：62220004 已加入拦截
        </div>
        <div class="notif-item">
          <span class="notif-dot" />平台公告：今晚 02:00 - 02:30 例行维护
        </div>
      </el-popover>
      <span class="env-tag">生产环境</span>
      <span class="user-chip">👤 张三 · 总行管理员</span>
    </div>
    <div v-else-if="isAdminRoute" class="role-chip">👔 当前角色：总行管理员</div>
  </header>
  <main class="main" :class="{ flush: isChatRoute, wide: isWideRoute }">
    <RouterView />
  </main>
</template>
