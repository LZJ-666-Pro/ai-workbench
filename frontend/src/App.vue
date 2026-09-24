<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, Bell } from '@element-plus/icons-vue'
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

/** 顶栏用户下拉：管理后台（仅 ADMIN）与退出登录 */
function onUserCommand(command: string) {
  if (command === 'admin') {
    router.push('/admin')
    return
  }
  if (command === 'logout') {
    confirmLogout()
  }
}

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
    <!-- 右上角操作区：通知铃铛 · 环境胶囊 · 用户头像下拉（参照企业控制台样式） -->
    <div class="top-right">
      <template v-if="route.path === '/'">
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
        <span class="env-tag"><i class="env-dot" />生产环境</span>
      </template>
      <el-dropdown v-if="auth.user" trigger="click" @command="onUserCommand">
        <span class="user-chip user-chip-link" title="账号菜单">
          <span class="avatar">{{ auth.user.displayName.charAt(0) }}</span>
          <span class="user-name">{{ auth.user.displayName }} · {{ roleLabel(auth.user.platformRole) }}</span>
          <el-icon class="caret"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="auth.user.platformRole === 'ADMIN'" command="admin">📊 管理后台</el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
  <main class="main" :class="{ flush: isChatRoute, wide: isWideRoute, 'no-top': route.path === '/login' }">
    <RouterView />
  </main>
</template>

<style scoped>
/* 右上角操作区：铃铛 / 环境胶囊 / 用户菜单成组靠右 */
.top-right {
  display: inline-flex;
  align-items: center;
  gap: 14px;
}
/* 环境胶囊：绿点 + 文字，圆角胶囊 */
.env-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  padding: 4px 12px;
}
.env-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #0a8f3c;
  box-shadow: 0 0 0 3px rgba(10, 143, 60, 0.15);
}
/* 可点击的用户胶囊：头像 + 姓名 + 下拉箭头 */
.user-chip-link {
  cursor: pointer;
  gap: 9px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.user-chip-link:hover {
  border-color: #9fbcd9;
  box-shadow: 0 2px 8px rgba(11, 79, 158, 0.12);
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2f7bff, #0b4f9e);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 12.5px;
}
.caret {
  font-size: 12px;
  color: #8a9099;
}
</style>
