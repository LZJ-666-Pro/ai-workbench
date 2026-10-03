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

/** 数据源/设置页用宽容器（1280px）；工作台与应用中心是通栏门户（portal），自己管宽度 */
const isWideRoute = computed(() => ['/datasources', '/developers', '/settings'].includes(route.path))

/**
 * 工作台与应用中心自己管理内外边距：上下文条与页脚要做成通栏（左右贴边、边框通到底），
 * 只有让 .main 让出宽度控制权才做得到。其他页面不受影响。
 */
const isPortalRoute = computed(() => ['/', '/apps'].includes(route.path))

/** 顶栏固定五页签（管理后台由侧栏菜单承担导航，不显示顶栏页签） */
const navLinks = [
  { to: '/', label: '工作台' },
  { to: '/apps', label: '应用中心' },
  { to: '/knowledge', label: '知识库' },
  { to: '/datasources', label: '数据源' },
  { to: '/settings', label: '设置' },
]

/** 铃铛与环境切换只在五个平台级页签页面显示；聊天工作区/后台/日志页保持简洁 */
const isTopPage = computed(() => navLinks.some(l => l.to === route.path))

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
      <span class="brand-mark"><span class="brand-mark-face">智</span></span>
      <span class="brand-text">智汇工作台</span>
      <span class="brand-tag">企业智能 Agent 平台</span>
    </RouterLink>
    <nav v-if="!isAdminRoute">
      <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
    </nav>
    <!-- 右上角操作区：全局搜索 · 通知铃铛 · 环境切换 · 用户下拉（参照企业控制台样式） -->
    <div class="top-right">
      <GlobalSearch v-if="showSearch" />
      <template v-if="isTopPage">
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
/* 品牌标识：深蓝菱形 Logo（旋转 45° 的圆角方块，内部文字反向旋转保持正立） */
.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}
.brand-mark {
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: linear-gradient(135deg, #1560b8, #0b3f7e);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transform: rotate(45deg);
  margin: 0 4px;
}
.brand-mark-face {
  color: #fff;
  font-size: 11.5px;
  font-weight: 800;
  transform: rotate(-45deg);
  line-height: 1;
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
