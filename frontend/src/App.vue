<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Bell } from '@element-plus/icons-vue'
import { auth, logout, roleLabel } from './api/auth'
import { notifications } from './api/platform'
import GlobalSearch from './components/GlobalSearch.vue'

const route = useRoute()
const router = useRouter()

/** 铃铛角标只数「可点击去处理」的条目：占位文案（如"暂无待处理事项"）不算待办 */
const notifCount = computed(() => notifications.value.filter(n => n.to).length)

/**
 * 全局搜索显示范围：登录页没有顶栏；管理后台有自己的侧栏与上下文，
 * 塞一个平台级搜索进去只会干扰，因此这两处不显示。
 */
const showSearch = computed(() => route.path !== '/login' && !route.path.startsWith('/admin'))

/** 当前环境。只有生产环境可用——把没开通的环境做成可点的选项，本身就是一种假数据 */
const currentEnv = '生产环境'

function onEnvCommand(command: string) {
  if (command === 'prod') {
    ElMessage.info('当前已在生产环境')
  }
}
/** 聊天工作区/后台/登录页/日志页是全屏布局，不走 .main 的限宽布局 */
const isChatRoute = computed(() =>
  ['/bank', '/knowledge', '/interview', '/admin', '/login', '/logs'].some(p => route.path.startsWith(p)),
)

const isAdminRoute = computed(() => route.path.startsWith('/admin'))

/** 首页与开发者页需要更宽的容器（1280px），体现平台容量感 */
const isWideRoute = computed(() => ['/', '/developers'].includes(route.path))

/**
 * 首页工作台自己管理内外边距：上下文条与页脚要做成通栏（左右贴边、边框通到底），
 * 只有让 .main 让出宽度控制权才做得到。其他页面不受影响。
 */
const isPortalRoute = computed(() => route.path === '/')

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
  if (route.path === '/logs') {
    return platformLinks
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
    <RouterLink to="/" class="brand">
      <span class="brand-mark">智</span>
      <span class="brand-text">智汇工作台</span>
    </RouterLink>
    <nav v-if="navLinks.length">
      <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
    </nav>
    <!-- 右上角操作区：全局搜索 · 通知铃铛 · 环境切换 · 用户下拉（参照企业控制台样式） -->
    <div class="top-right">
      <GlobalSearch v-if="showSearch" />
      <template v-if="route.path === '/'">
        <el-popover placement="bottom-end" :width="300" trigger="click">
          <template #reference>
            <!-- 角标取自工作台接口的真实待办条数（通知内容与数字必须一致；
                 原先写死 3，点开却只有 3 条静态文案） -->
            <el-badge :value="notifCount" :hidden="notifCount === 0" :offset="[-4, 4]" class="bell-wrap">
              <el-icon class="bell"><Bell /></el-icon>
            </el-badge>
          </template>
          <div class="notif-title">通知中心</div>
          <div v-if="!notifications.length" class="notif-item">
            <span class="notif-dot" />暂无待处理事项
          </div>
          <div v-for="(n, i) in notifications" :key="i" class="notif-item">
            <span class="notif-dot" :class="{ warn: n.level === 'warn' }" />{{ n.text }}
          </div>
        </el-popover>
        <el-dropdown trigger="click" @command="onEnvCommand">
          <span class="env-tag env-tag-btn">
            <i class="env-dot" />{{ currentEnv }}<el-icon class="caret"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="prod">生产环境</el-dropdown-item>
              <el-dropdown-item command="staging" disabled>预发环境（未开通）</el-dropdown-item>
              <el-dropdown-item command="dev" disabled>开发环境（未开通）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
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
  <main
    class="main"
    :class="{ flush: isChatRoute, wide: isWideRoute, portal: isPortalRoute, 'no-top': route.path === '/login' }"
  >
    <RouterView />
  </main>
</template>

<style scoped>
/* 品牌标识：深蓝圆角方块 + 字，替代原先的 emoji（emoji 在不同系统渲染差异大） */
.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}
.brand-mark {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: linear-gradient(135deg, #1560b8, #0b3f7e);
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.brand-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

/* 右上角操作区：搜索 / 铃铛 / 环境 / 用户成组靠右 */
.top-right {
  display: inline-flex;
  align-items: center;
  gap: 14px;
}
/* 环境胶囊做成可点：带下拉箭头，与其他下拉控件语言一致 */
.env-tag-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
}
.env-tag-btn .caret {
  font-size: 11px;
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
