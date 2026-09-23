<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import type { OrderView, TxnView } from '../../api/admin'

const tab = ref<'flow' | 'orders'>('flow')

// ---- 全部流水 ----
const flowQuery = reactive({ accountNo: '', direction: 'all', page: 1, size: 10 })
const flowRows = ref<TxnView[]>([])
const flowTotal = ref(0)
const flowLoading = ref(false)

async function loadFlow() {
  flowLoading.value = true
  try {
    const r = await adminApi.transactions(flowQuery.accountNo, flowQuery.direction, flowQuery.page, flowQuery.size)
    flowRows.value = r.list
    flowTotal.value = r.total
  } finally {
    flowLoading.value = false
  }
}

function resetFlow() {
  flowQuery.page = 1
  loadFlow()
}

// ---- 转账订单 ----
const orderQuery = reactive({ status: '', page: 1, size: 10 })
const orderRows = ref<OrderView[]>([])
const orderTotal = ref(0)
const orderLoading = ref(false)

const statusTag: Record<string, string> = {
  PENDING: 'warning',
  EXECUTED: 'success',
  CANCELLED: 'info',
  REJECTED: 'danger',
}

async function loadOrders() {
  orderLoading.value = true
  try {
    const r = await adminApi.transferOrders(orderQuery.status, orderQuery.page, orderQuery.size)
    orderRows.value = r.list
    orderTotal.value = r.total
  } finally {
    orderLoading.value = false
  }
}

function resetOrders() {
  orderQuery.page = 1
  loadOrders()
}

onMounted(() => {
  loadFlow()
  loadOrders()
})

const fmt = (n: number) => n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
</script>

<template>
  <div class="page-card">
    <el-tabs v-model="tab">
      <el-tab-pane label="交易流水" name="flow">
        <div class="toolbar">
          <el-input
            v-model="flowQuery.accountNo"
            placeholder="按账号精确筛选"
            clearable
            style="width: 200px"
            @change="resetFlow"
          />
          <el-radio-group v-model="flowQuery.direction" @change="resetFlow">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="in">收入</el-radio-button>
            <el-radio-button value="out">支出</el-radio-button>
          </el-radio-group>
        </div>
        <el-table v-loading="flowLoading" :data="flowRows" stripe>
          <el-table-column prop="id" label="流水号" width="90" />
          <el-table-column label="账号" width="120">
            <template #default="{ row }"><span class="mono">{{ row.accountNo }}</span></template>
          </el-table-column>
          <el-table-column prop="owner" label="户名" width="110" />
          <el-table-column prop="description" label="摘要" min-width="220" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }"><span class="dim">{{ row.createdAt }}</span></template>
          </el-table-column>
          <el-table-column label="金额（元）" width="130" align="right">
            <template #default="{ row }">
              <span :class="row.amount > 0 ? 'in' : 'out'">{{ row.amount > 0 ? '+' : '' }}{{ fmt(row.amount) }}</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="没有符合条件的流水" /></template>
        </el-table>
        <el-pagination
          v-model:current-page="flowQuery.page"
          :page-size="flowQuery.size"
          :total="flowTotal"
          layout="total, prev, pager, next"
          background
          class="pager"
          @current-change="loadFlow"
        />
      </el-tab-pane>

      <el-tab-pane label="AI 转账订单" name="orders">
        <div class="toolbar">
          <span class="toolbar-label">订单状态：</span>
          <el-radio-group v-model="orderQuery.status" @change="resetOrders">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="PENDING">待确认</el-radio-button>
            <el-radio-button value="EXECUTED">已执行</el-radio-button>
            <el-radio-button value="CANCELLED">已取消</el-radio-button>
            <el-radio-button value="REJECTED">已拒绝</el-radio-button>
          </el-radio-group>
        </div>
        <el-table v-loading="orderLoading" :data="orderRows" stripe>
          <el-table-column prop="id" label="单号" width="70" />
          <el-table-column label="付款账号" width="120">
            <template #default="{ row }"><span class="mono">{{ row.fromAccount }}</span></template>
          </el-table-column>
          <el-table-column label="收款账号" width="120">
            <template #default="{ row }"><span class="mono">{{ row.toAccount }}</span></template>
          </el-table-column>
          <el-table-column label="金额（元）" width="130" align="right">
            <template #default="{ row }"><span class="mono">{{ fmt(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column prop="reason" label="附言" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="(statusTag[row.status] || 'info') as any" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="170">
            <template #default="{ row }"><span class="dim">{{ row.createdAt }}</span></template>
          </el-table-column>
          <template #empty><el-empty description="没有符合条件的订单" /></template>
        </el-table>
        <el-pagination
          v-model:current-page="orderQuery.page"
          :page-size="orderQuery.size"
          :total="orderTotal"
          layout="total, prev, pager, next"
          background
          class="pager"
          @current-change="loadOrders"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.toolbar-label {
  font-size: 13px;
  color: #7a8798;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
  font-variant-numeric: tabular-nums;
}

.in {
  color: #0a8f3c;
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.out {
  color: #d93026;
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.dim {
  color: #98a2b0;
  font-size: 12px;
}
</style>
