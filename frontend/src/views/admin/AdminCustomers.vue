<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { adminApi } from '../../api/admin'
import type { AccountView, TxnView } from '../../api/admin'

const query = reactive({ keyword: '', type: 'all', page: 1, size: 10 })
const rows = ref<AccountView[]>([])
const total = ref(0)
const loading = ref(false)

// 流水抽屉
const drawerVisible = ref(false)
const drawerAccount = ref<AccountView | null>(null)
const drawerRows = ref<TxnView[]>([])
const drawerTotal = ref(0)
const drawerPage = reactive({ page: 1, size: 8 })
const drawerLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await adminApi.accounts(query.keyword, query.type, query.page, query.size)
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

async function openDrawer(row: AccountView) {
  drawerAccount.value = row
  drawerVisible.value = true
  drawerPage.page = 1
  await loadDrawer()
}

async function loadDrawer() {
  if (!drawerAccount.value) return
  drawerLoading.value = true
  try {
    const r = await adminApi.accountTransactions(drawerAccount.value.accountNo, drawerPage.page, drawerPage.size)
    drawerRows.value = r.list
    drawerTotal.value = r.total
  } finally {
    drawerLoading.value = false
  }
}

watch(() => [query.keyword, query.type], () => {
  query.page = 1
  load()
})

onMounted(load)

const fmt = (n: number) => n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const typeText = (t: string) => (t === 'corporate' ? '对公' : '个人')
</script>

<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="按账号或户名搜索"
        clearable
        style="width: 240px"
        :prefix-icon="undefined"
      />
      <el-radio-group v-model="query.type">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="personal">个人账户</el-radio-button>
        <el-radio-button value="corporate">对公账户</el-radio-button>
      </el-radio-group>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="accountNo" label="账号" width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.accountNo }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="户名" min-width="120" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.type === 'corporate' ? 'warning' : 'primary'" effect="plain" size="small">
            {{ typeText(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="余额（元）" width="160" align="right">
        <template #default="{ row }">
          <span class="amount" :class="{ strong: row.balance > 100000 }">{{ fmt(row.balance) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="txnCount" label="流水笔数" width="100" align="center" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click="openDrawer(row)">查看流水</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的账户" /></template>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      background
      class="pager"
      @current-change="load"
    />

    <!-- 流水明细抽屉 -->
    <el-drawer v-model="drawerVisible" :title="`${drawerAccount?.owner}（${drawerAccount?.accountNo}）的流水明细`" size="560px">
      <div v-loading="drawerLoading">
        <el-table :data="drawerRows" stripe size="small">
          <el-table-column prop="createdAt" label="时间" width="160">
            <template #default="{ row }">
              <span class="dim">{{ row.createdAt }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column label="金额（元）" width="120" align="right">
            <template #default="{ row }">
              <span :class="row.amount > 0 ? 'in' : 'out'">{{ row.amount > 0 ? '+' : '' }}{{ fmt(row.amount) }}</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无流水" /></template>
        </el-table>
        <el-pagination
          v-model:current-page="drawerPage.page"
          :page-size="drawerPage.size"
          :total="drawerTotal"
          layout="total, prev, pager, next"
          background
          small
          class="pager"
          @current-change="loadDrawer"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.amount {
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
}

.amount.strong {
  color: #0b4f9e;
  font-weight: 700;
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
