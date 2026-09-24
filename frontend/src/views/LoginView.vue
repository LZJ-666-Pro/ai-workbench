<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  View, Hide, Key, Postcard, Connection,
  User, Lock, ChatDotRound, Coin, Share,
} from '@element-plus/icons-vue'
import { login } from '../api/auth'

const router = useRouter()
const route = useRoute()

/** 登录身份选项卡：企业平台先选身份再登录，体现多角色边界 */
const TABS = [
  { key: 'personal', label: '个人用户', placeholder: '手机号 / 用户名' },
  { key: 'corporate', label: '企业用户', placeholder: '企业账号 / 操作员编号' },
  { key: 'staff', label: '内部员工', placeholder: '工号 / 邮箱' },
] as const
type TabKey = (typeof TABS)[number]['key']

const activeTab = ref<TabKey>('personal')
const activePlaceholder = computed(() => TABS.find(t => t.key === activeTab.value)!.placeholder)
const TAB_LABEL: Record<TabKey, string> = { personal: '个人用户', corporate: '企业用户', staff: '内部员工' }

/** 测试账号：归属到身份选项卡，点击填充（密码 Demo@2026） */
const DEMO_PASSWORD = 'Demo@2026'
const testAccounts: { tab: TabKey; username: string; name: string; desc: string }[] = [
  { tab: 'personal', username: 'zhangsan', name: '张三', desc: '零售客户 · 管理员' },
  { tab: 'corporate', username: 'corp001', name: '星辰科技', desc: '对公客户' },
  { tab: 'staff', username: 'staff001', name: '小陈', desc: '内部员工 · 只读' },
]
/** 账号 → 归属身份（演示账号固定映射；未登记账号直接交后端校验） */
const ACCOUNT_TAB: Record<string, TabKey> =
  Object.fromEntries(testAccounts.map(a => [a.username, a.tab]))

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const rememberMe = ref(true)
const loading = ref(false)
const errorMsg = ref('')

/** 当前身份标签对应的测试账号（点击填充并高亮） */
const tabAccount = computed(() => testAccounts.find(a => a.tab === activeTab.value)!)

function fillAccount() {
  username.value = tabAccount.value.username
  password.value = DEMO_PASSWORD
  errorMsg.value = ''
}

async function submit() {
  if (!username.value.trim() || !password.value || loading.value) return
  // 身份边界：演示账号必须在其归属标签下登录，防止"以员工标签登客户账号"的混淆
  const ownerTab = ACCOUNT_TAB[username.value.trim()]
  if (ownerTab && ownerTab !== activeTab.value) {
    errorMsg.value = `该账号属于「${TAB_LABEL[ownerTab]}」身份，请切换到对应标签后登录`
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await login(username.value.trim(), password.value)
    // 支持登录前被拦截的原始目标，如 /admin/dashboard
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '登录失败，请稍后再试'
  } finally {
    loading.value = false
  }
}

/** 未开通能力的占位交互（U盾/证书/SSO/忘记密码/开通账号） */
function notAvailable(feature: string) {
  ElMessage.info(`${feature}：演示版暂未开通，请使用账号密码登录`)
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区：平台定位 + 泛化能力展示（不暴露具体业务应用） -->
    <section class="brand-pane">
      <div class="brand-top">
        <span class="logo-mark">智</span>
        <span class="brand-name">智汇工作台</span>
      </div>

      <div class="brand-body">
        <span class="brand-eyebrow">AI 工作台</span>
        <h2 class="brand-title">一个工作台，<br>承载企业所有的 AI 能力</h2>
        <p class="brand-desc">对话、检索、流程三类 Agent 统一接入 · 统一身份 · 统一入口 · 全程审计</p>

        <!-- 平台能力概览：只讲能力类型，不点名具体应用 -->
        <div class="app-preview">
          <div class="app-card">
            <span class="app-icon"><el-icon><ChatDotRound /></el-icon></span>
            <span class="app-meta">
              <span class="app-name">智能对话 Agent</span>
              <span class="app-type">业务问答 · 办理 · 人工确认卡</span>
            </span>
            <span class="app-status run">运行中</span>
          </div>
          <div class="app-card">
            <span class="app-icon"><el-icon><Coin /></el-icon></span>
            <span class="app-meta">
              <span class="app-name">知识检索 Agent</span>
              <span class="app-type">企业知识库 · 引用可溯源</span>
            </span>
            <span class="app-status building">建设中</span>
          </div>
          <div class="app-card">
            <span class="app-icon"><el-icon><Share /></el-icon></span>
            <span class="app-meta">
              <span class="app-name">流程自动化 Agent</span>
              <span class="app-type">审批流转 · 结构化报告生成</span>
            </span>
            <span class="app-status ready">可用</span>
          </div>
        </div>

        <div class="brand-stats">
          <div class="stat"><span class="stat-label">智能体应用</span><span class="stat-num">12</span></div>
          <div class="stat-line"></div>
          <div class="stat"><span class="stat-label">业务工具</span><span class="stat-num">28</span></div>
          <div class="stat-line"></div>
          <div class="stat"><span class="stat-label">调用成功率</span><span class="stat-num">99.5%</span></div>
        </div>
      </div>

      <div class="brand-foot">© 2026 智汇工作台 v1.0.0 · 京ICP备20260088号</div>
    </section>

    <!-- 右侧表单区 -->
    <section class="form-pane">
      <div class="form-top">
        <button type="button" class="form-top-hint" @click="notAvailable('账号开通')">还没有账号？联系管理员开通</button>
        <span class="env-tag">生产环境</span>
      </div>

      <div class="form-body">
        <h1 class="login-title">欢迎回来</h1>
        <p class="login-sub">登录工作台，进入你的专属空间</p>

        <!-- 登录身份选项卡 -->
        <div class="tab-bar" role="tablist">
          <button
            v-for="t in TABS"
            :key="t.key"
            type="button"
            class="tab-item"
            :class="{ active: activeTab === t.key }"
            role="tab"
            :aria-selected="activeTab === t.key"
            @click="activeTab = t.key; errorMsg = ''"
          >
            {{ t.label }}
          </button>
        </div>

        <form class="login-form" @submit.prevent="submit">
          <label class="field">
            <span class="field-label">{{ TAB_LABEL[activeTab] }}账号</span>
            <span class="input-wrap">
              <el-icon class="input-icon"><User /></el-icon>
              <input
                v-model="username"
                class="field-input has-leading-icon"
                type="text"
                :placeholder="activePlaceholder"
                autocomplete="username"
              >
            </span>
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <span class="input-wrap">
              <el-icon class="input-icon"><Lock /></el-icon>
              <input
                v-model="password"
                class="field-input has-leading-icon"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                autocomplete="current-password"
              >
              <button
                type="button"
                class="eye-btn"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <el-icon><component :is="showPassword ? Hide : View" /></el-icon>
              </button>
            </span>
          </label>

          <div class="form-row">
            <label class="remember">
              <input v-model="rememberMe" type="checkbox" class="checkbox">
              记住我
            </label>
            <button type="button" class="link-btn" @click="notAvailable('忘记密码')">忘记密码?</button>
          </div>

          <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>

          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true"></span>
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </form>

        <!-- 其他登录方式：企业平台标志位 -->
        <div class="alt-divider"><span>其他登录方式</span></div>
        <div class="alt-list">
          <button type="button" class="alt-btn" @click="notAvailable('U盾登录')">
            <el-icon><Key /></el-icon>U盾登录
          </button>
          <button type="button" class="alt-btn" @click="notAvailable('数字证书登录')">
            <el-icon><Postcard /></el-icon>数字证书
          </button>
          <button type="button" class="alt-btn" @click="notAvailable('SSO 单点登录')">
            <el-icon><Connection /></el-icon>SSO 单点登录
          </button>
        </div>

        <!-- 测试账号：跟随身份选项卡变化 -->
        <div class="demo-area">
          <div class="demo-caption">测试账号（密码 {{ DEMO_PASSWORD }}，点击填充）</div>
          <button
            type="button"
            class="demo-chip"
            :class="{ filled: username === tabAccount.username }"
            @click="fillAccount"
          >
            <span class="demo-name">{{ tabAccount.name }}</span>
            <span class="demo-desc">{{ tabAccount.desc }}</span>
          </button>
        </div>
      </div>

      <div class="form-foot">
        <span class="security-pill">🔒 本系统为内部业务系统，所有操作将被记录并纳入审计</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 骨架：左右分栏——左品牌深蓝、右表单白底，占满 topbar 以下全部空间 */
.login-page {
  height: 100%;
  display: flex;
  overflow: hidden;
}

/* ============ 左侧品牌区 ============ */
.brand-pane {
  flex: 0 0 46%;
  max-width: 620px;
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 36px 48px 28px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(158deg, #0e3a75 0%, #0b4f9e 48%, #1a63bd 100%);
}
/* 装饰：右上/左下柔光，增强品牌纵深 */
.brand-pane::before {
  content: '';
  position: absolute;
  right: -160px;
  top: -160px;
  width: 460px;
  height: 460px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.14) 0%, transparent 65%);
  pointer-events: none;
}
.brand-pane::after {
  content: '';
  position: absolute;
  left: -120px;
  bottom: -180px;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.08) 0%, transparent 60%);
  pointer-events: none;
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 10px;
  position: relative;
  z-index: 1;
}
.logo-mark {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #fff;
  color: #0b4f9e;
  font-size: 19px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
.brand-name {
  font-size: 16.5px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.brand-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  position: relative;
  z-index: 1;
  padding: 20px 0;
}
.brand-eyebrow {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 6px;
  color: rgba(255, 255, 255, 0.66);
  margin-bottom: 8px;
}
.brand-title {
  margin: 0 0 12px;
  font-size: 29px;
  line-height: 1.38;
  font-weight: 700;
  letter-spacing: 1px;
}
.brand-desc {
  margin: 0 0 26px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.78);
  line-height: 1.7;
}

/* 平台能力概览：泛化能力卡片，不暴露具体业务应用 */
.app-preview {
  display: flex;
  flex-direction: column;
  gap: 11px;
  margin-bottom: 28px;
}
.app-card {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 12px 16px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(2px);
  transition: transform 0.15s, background 0.15s, border-color 0.15s;
}
.app-card:hover {
  transform: translateY(-2px);
  background: rgba(255, 255, 255, 0.13);
  border-color: rgba(255, 255, 255, 0.35);
}
.app-icon {
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.26);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  color: rgba(255, 255, 255, 0.95);
}
.app-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.app-name {
  font-size: 14.5px;
  font-weight: 700;
}
.app-type {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.62);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.app-status {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 999px;
}
.app-status.run {
  color: #7ee2a8;
  background: rgba(46, 160, 90, 0.25);
  border: 1px solid rgba(126, 226, 168, 0.4);
}
.app-status.building {
  color: #ffd88a;
  background: rgba(191, 138, 32, 0.25);
  border: 1px solid rgba(255, 216, 138, 0.4);
}
.app-status.ready {
  color: #9ecbff;
  background: rgba(64, 120, 200, 0.3);
  border: 1px solid rgba(158, 203, 255, 0.45);
}

/* 统计：无边框三列，标签在上、数字在下 */
.brand-stats {
  display: flex;
  align-items: center;
  gap: 28px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.stat-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.66);
}
.stat-num {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.stat-line {
  width: 1px;
  height: 30px;
  background: rgba(255, 255, 255, 0.25);
}

.brand-foot {
  position: relative;
  z-index: 1;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
}

/* ============ 右侧表单区 ============ */
.form-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fbfcfe;
  min-width: 0;
  position: relative;
  z-index: 0;
}
/* 装饰：右下同心圆环，给表单区一点空间感 */
.form-pane::before,
.form-pane::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  border: 1px solid #e6ebf3;
  z-index: -1;
  pointer-events: none;
}
.form-pane::before {
  right: -110px;
  bottom: -110px;
  width: 340px;
  height: 340px;
}
.form-pane::after {
  right: -40px;
  bottom: -40px;
  width: 200px;
  height: 200px;
}
.form-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 28px 0;
}
.form-top-hint {
  border: none;
  background: transparent;
  padding: 0;
  font-size: 12.5px;
  color: #3a76d6;
  cursor: pointer;
}
.form-top-hint:hover { text-decoration: underline; }
.env-tag {
  font-size: 12px;
  color: #0a8f3c;
  background: #e8f7ee;
  border: 1px solid #b7e2c6;
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}

.form-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 384px;
  margin: 0 auto;
  padding: 12px 0;
}

.login-title {
  margin: 0 0 6px;
  font-size: 26px;
  color: #12263f;
}
.login-sub {
  margin: 0 0 22px;
  font-size: 13px;
  color: #8a97a8;
}

/* 身份选项卡：分段式，激活项品牌蓝 */
.tab-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 18px;
  background: #fff;
}
.tab-item {
  padding: 9px 0;
  font-size: 13px;
  background: #fff;
  color: #5a6b80;
  border: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.tab-item + .tab-item {
  border-left: 1px solid #e5e8ec;
}
.tab-item:hover { background: #f2f7fc; }
.tab-item.active {
  background: #1265e0;
  color: #fff;
  font-weight: 600;
}

/* 表单 */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12.5px;
  color: #5a6b80;
  font-weight: 600;
}
/* 输入框：白底 + 浅灰描边 + 4px 圆角 + 左侧语义图标，聚焦品牌蓝 + 浅蓝外发光 */
.field-input {
  height: 42px;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 12px;
  font-size: 14px;
  color: #12263f;
  background: #fff;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.field-input.has-leading-icon {
  padding-left: 38px;
}
.field-input::placeholder { color: #b3bec9; }
.field-input:focus {
  border-color: #1265e0;
  box-shadow: 0 0 0 3px rgba(18, 101, 224, 0.12);
}
/* 浏览器自动填充会把输入框染成淡蓝色：强制回填白色底、正常字色 */
.field-input:-webkit-autofill,
.field-input:-webkit-autofill:hover,
.field-input:-webkit-autofill:focus {
  -webkit-box-shadow: 0 0 0 1000px #fff inset;
  -webkit-text-fill-color: #12263f;
  caret-color: #12263f;
  transition: background-color 9999s ease-in-out 0s;
}

.input-wrap {
  position: relative;
  display: block;
}
.input-icon {
  position: absolute;
  left: 13px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 15px;
  color: #98a2b0;
  pointer-events: none;
}
.input-wrap .field-input:focus ~ .input-icon,
.input-wrap:focus-within .input-icon { color: #1265e0; }

/* 密码框眼睛切换 */
.eye-btn {
  position: absolute;
  right: 5px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #98a2b0;
  cursor: pointer;
  border-radius: 4px;
}
.eye-btn:hover { color: #1265e0; }

/* 记住我 / 忘记密码 */
.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: -2px;
}
.remember {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: #5a6b80;
  cursor: pointer;
  user-select: none;
}
.checkbox {
  width: 14px;
  height: 14px;
  accent-color: #1265e0;
  cursor: pointer;
}
.link-btn {
  border: none;
  background: transparent;
  color: #1265e0;
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
}
.link-btn:hover { text-decoration: underline; }

.error-tip {
  font-size: 12.5px;
  color: #c0392b;
  background: #fdeeec;
  border: 1px solid #f5c6c0;
  border-radius: 4px;
  padding: 7px 10px;
}

/* 登录按钮 + loading 态 */
.submit-btn {
  height: 44px;
  margin-top: 4px;
  border: none;
  border-radius: 4px;
  background: #1265e0;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 2px 6px rgba(18, 101, 224, 0.28);
  transition: background 0.15s, box-shadow 0.15s;
}
.submit-btn:hover {
  background: #0e56c4;
  box-shadow: 0 3px 8px rgba(18, 101, 224, 0.34);
}
.submit-btn:disabled { opacity: 0.65; cursor: not-allowed; }
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 其他登录方式 */
.alt-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 12px;
  color: #98a2b0;
  font-size: 12px;
}
.alt-divider::before,
.alt-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: #e5e8ec;
}
.alt-list {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.alt-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 36px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  color: #4a5a6d;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.alt-btn:hover {
  border-color: #1265e0;
  color: #1265e0;
  background: #f5f9ff;
}

/* 测试账号：跟随选项卡，单卡填充 + 选中高亮 */
.demo-area {
  margin-top: 18px;
  border-top: 1px dashed #e0e6ee;
  padding-top: 12px;
}
.demo-caption {
  font-size: 12px;
  color: #98a2b0;
  margin-bottom: 8px;
}
.demo-chip {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e5e8ec;
  background: #fafcfe;
  border-radius: 4px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12.5px;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.demo-chip:hover {
  border-color: #9fbcd9;
  background: #f2f7fc;
}
.demo-chip.filled {
  border-color: #1265e0;
  background: #eef4fd;
}
.demo-name {
  font-weight: 700;
  color: #12263f;
  min-width: 56px;
}
.demo-desc { color: #8a97a8; }

/* 底部安全提示：银行内部系统标配（浅蓝胶囊） */
.form-foot {
  display: flex;
  justify-content: center;
  padding: 14px 20px 18px;
}
.security-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #4a5a6d;
  background: #eef4fd;
  border: 1px solid #d7e5fa;
  border-radius: 999px;
  padding: 7px 16px;
}

/* 窄屏：隐藏品牌区，表单占满 */
@media (max-width: 960px) {
  .brand-pane { display: none; }
  .form-body {
    width: min(420px, calc(100% - 48px));
  }
}
</style>
