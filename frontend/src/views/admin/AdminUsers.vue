<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import type { UserAdminView } from '../../api/admin'
import { roleLabel } from '../../api/auth'

const rows = ref<UserAdminView[]>([])
const loading = ref(false)

// 新增用户弹窗（管理员开通制：账号由管理员在此创建并落库）
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  platformRole: 'USER' as 'ADMIN' | 'USER',
  identityId: 'zhangsan',
})

// 重置密码弹窗
const resetVisible = ref(false)
const resetting = ref(false)
const resetTarget = ref<UserAdminView | null>(null)
const resetPassword = ref('')

/** 银行身份展示名（与后端 BankIdentity 枚举对应） */
const IDENTITY_LABELS: Record<string, string> = {
  zhangsan: '零售客户',
  staff001: '内部员工',
  corp001: '对公客户',
}

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.users()
  } finally {
    loading.value = false
  }
}

async function submitCreate() {
  if (!createForm.username || !createForm.displayName || !createForm.password) {
    ElMessage.warning('请完整填写用户名、姓名和初始密码')
    return
  }
  creating.value = true
  try {
    const r = await adminApi.createUser({ ...createForm })
    ElMessage.success(r.message)
    createVisible.value = false
    createForm.username = ''
    createForm.displayName = ''
    createForm.password = ''
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '开通失败')
  } finally {
    creating.value = false
  }
}

async function toggleStatus(row: UserAdminView) {
  const action = row.enabled ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}账号「${row.displayName}（${row.username}）」吗？${row.enabled ? '停用后该用户立即无法登录与使用系统。' : ''}`,
      `${action}账号`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const r = await adminApi.setUserStatus(row.id, !row.enabled)
    ElMessage.success(r.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : `${action}失败`)
  }
}

function openReset(row: UserAdminView) {
  resetTarget.value = row
  resetPassword.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetTarget.value) return
  if (resetPassword.value.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  resetting.value = true
  try {
    const r = await adminApi.resetUserPassword(resetTarget.value.id, resetPassword.value)
    ElMessage.success(r.message)
    resetVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重置失败')
  } finally {
    resetting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="toolbar">
      <span class="hint">账号由管理员在此开通，新用户用开通时的用户名和密码登录工作台。</span>
      <el-button type="primary" @click="createVisible = true">新增用户</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="username" label="用户名" width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.username }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="displayName" label="姓名" min-width="110" />
      <el-table-column label="平台角色" width="120">
        <template #default="{ row }">
          <el-tag :type="row.platformRole === 'ADMIN' ? 'danger' : 'info'" effect="plain" size="small">
            {{ roleLabel(row.platformRole) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="银行身份" width="110">
        <template #default="{ row }">
          {{ IDENTITY_LABELS[row.identityId] ?? row.identityId }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'warning'" effect="plain" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170">
        <template #default="{ row }">
          <span class="dim">{{ row.createdAt }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :type="row.enabled ? 'warning' : 'success'"
            plain
            @click="toggleStatus(row)"
          >{{ row.enabled ? '停用' : '启用' }}</el-button>
          <el-button size="small" type="primary" plain @click="openReset(row)">重置密码</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无用户" /></template>
    </el-table>

    <!-- 新增用户弹窗 -->
    <el-dialog v-model="createVisible" title="开通新用户" width="460px">
      <el-form label-width="90px" label-position="left">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" placeholder="3-32 位字母、数字或下划线" maxlength="32" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="createForm.displayName" placeholder="工作台内展示的名字" maxlength="32" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="平台角色">
          <el-radio-group v-model="createForm.platformRole">
            <el-radio-button value="USER">普通用户</el-radio-button>
            <el-radio-button value="ADMIN">总行管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="银行身份">
          <el-select v-model="createForm.identityId" style="width: 100%">
            <el-option label="零售客户" value="zhangsan" />
            <el-option label="内部员工" value="staff001" />
            <el-option label="对公客户" value="corp001" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <span class="form-hint">演示环境：同身份共享银行助手数据；知识库按人隔离将在后续版本提供。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">开通</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="resetVisible" :title="`重置密码：${resetTarget?.displayName ?? ''}`" width="400px">
      <el-input v-model="resetPassword" type="password" show-password placeholder="新密码，至少 6 位" />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.hint {
  color: #98a2b0;
  font-size: 12.5px;
}

.form-hint {
  color: #98a2b0;
  font-size: 12px;
  line-height: 1.5;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.dim {
  color: #98a2b0;
  font-size: 12px;
}
</style>
