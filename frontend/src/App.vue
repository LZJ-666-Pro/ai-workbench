<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { auth, logout, roleLabel } from './api/auth'

const route = useRoute()
const router = useRouter()
/** 聊天工作区/后台/登录页是全屏布局，不走 .main 的限宽布局 */
const isChatRoute = computed(() =>
  ['/bank', '/knowledge', '/interview', '/admin', '/login'].some(p => route.path.startsWith(p)),
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

/** 顶栏用户胶囊：点击退出登录 */
async function confirmLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  logout()
  router.push('/login')
}
</script>

<template>
  <header v-if="route.path !== '/login'" class="topbar">
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
    </div>
    <span
      v-if="auth.user"
      class="user-chip user-chip-link"
      title="点击退出登录"
      @click="confirmLogout"
    >👤 {{ auth.user.displayName }} · {{ roleLabel(auth.user.platformRole) }}</span>
  </header>
  <main class="main" :class="{ flush: isChatRoute, wide: isWideRoute, 'no-top': route.path === '/login' }">
    <RouterView />
  </main>
</template>

<style scoped>
/* 可点击的用户胶囊：与全局 .user-chip 样式衔接，补充交互态 */
.user-chip-link {
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.user-chip-link:hover {
  border-color: #9fbcd9;
  color: #0b4f9e;
}
</style>
