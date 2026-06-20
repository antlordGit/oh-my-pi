<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { listTenants, deleteTenant, type TenantInfo } from '@/api/system'
import TenantForm from './TenantForm.vue'

const msg = useMessage()
const dialog = useDialog()
const refreshStats = inject<() => Promise<void>>('refreshStats')

const tenants = ref<TenantInfo[]>([])
const showForm = ref(false)
const editingTenant = ref<TenantInfo | null>(null)

async function load() {
  tenants.value = await listTenants()
}

function handleCreate() {
  editingTenant.value = null
  showForm.value = true
}
function handleEdit(t: TenantInfo) {
  editingTenant.value = t
  showForm.value = true
}
function handleDelete(t: TenantInfo) {
  dialog.warning({
    title: '删除租户',
    content: `确认删除租户「${t.tenantName}」？该租户下的角色数据将被级联删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteTenant(t.id)
        msg.success('已删除')
        await load()
        refreshStats?.()
      } catch (e: any) {
        msg.error(e?.response?.data?.error || '删除失败')
      }
    },
  })
}
function onFormSaved() {
  showForm.value = false
  load()
  refreshStats?.()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="header">
      <h2 class="title">租户管理</h2>
      <button v-permission="'omp:system:tenant:create'" class="btn-primary" @click="handleCreate">+ 创建租户</button>
    </div>

    <div v-if="!tenants.length" class="empty card">
      <span class="empty-icon">—</span>
      <p class="empty-text">暂无租户</p>
    </div>

    <div v-else class="card">
      <div class="table-head mono">
        <span style="width:40px">#</span>
        <span style="width:140px">租户编码</span>
        <span style="flex:1">租户名称</span>
        <span style="flex:1">描述</span>
        <span style="width:60px">状态</span>
        <span style="width:120px">创建时间</span>
        <span style="width:100px">操作</span>
      </div>
      <div v-for="(t, idx) in tenants" :key="t.id" class="table-row">
        <span class="mono dim" style="width:40px">{{ String(idx + 1).padStart(2, '0') }}</span>
        <span class="mono accent" style="width:140px">{{ t.tenantCode }}</span>
        <span style="flex:1">{{ t.tenantName }}</span>
        <span class="dim" style="flex:1;font-size:11px">{{ t.description || '—' }}</span>
        <span style="width:60px">
          <span class="tag" :class="t.enabled ? 'tag-success' : 'tag-danger'">{{ t.enabled ? '启用' : '禁用' }}</span>
        </span>
        <span class="mono dim" style="width:120px;font-size:11px">{{ t.createdAt?.slice(0, 10) || '—' }}</span>
        <span style="width:100px;display:inline-flex;gap:6px">
          <button v-permission="'omp:system:tenant:edit'" class="btn-mini" @click="handleEdit(t)">编辑</button>
          <button v-permission="'omp:system:tenant:delete'" class="btn-mini-danger" @click="handleDelete(t)">删除</button>
        </span>
      </div>
    </div>

    <TenantForm v-if="showForm" :tenant="editingTenant" @close="showForm = false" @saved="onFormSaved" />
  </div>
</template>
