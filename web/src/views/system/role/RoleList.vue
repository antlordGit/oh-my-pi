<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { listRoles, deleteRole, type RoleInfo } from '@/api/system'
import RoleForm from './RoleForm.vue'
import RoleMenuDialog from './RoleMenuDialog.vue'

const msg = useMessage()
const dialog = useDialog()
const refreshStats = inject<() => Promise<void>>('refreshStats')

const roles = ref<RoleInfo[]>([])
const showForm = ref(false)
const editingRole = ref<RoleInfo | null>(null)
const showMenuDialog = ref(false)
const menuRoleId = ref<number | null>(null)

async function load() {
  roles.value = await listRoles()
}

function handleCreate() {
  editingRole.value = null
  showForm.value = true
}
function handleEdit(r: RoleInfo) {
  editingRole.value = r
  showForm.value = true
}
function handleMenus(r: RoleInfo) {
  menuRoleId.value = r.id
  showMenuDialog.value = true
}
function handleDelete(r: RoleInfo) {
  dialog.warning({
    title: '删除角色',
    content: `确认删除角色「${r.roleName}」？角色关联的菜单和用户分配将被级联删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteRole(r.id)
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
function onMenusSaved() {
  showMenuDialog.value = false
  load()
  refreshStats?.()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="header">
      <h2 class="title">角色管理</h2>
      <button v-permission="'omp:system:role:create'" class="btn-primary" @click="handleCreate">+ 创建角色</button>
    </div>

    <div v-if="!roles.length" class="empty card">
      <span class="empty-icon">—</span>
      <p class="empty-text">暂无角色</p>
    </div>

    <div v-else class="card">
      <div class="table-head mono">
        <span style="width:40px">#</span>
        <span style="width:120px">角色编码</span>
        <span style="flex:1">角色名称</span>
        <span style="width:80px">租户ID</span>
        <span style="width:60px">状态</span>
        <span style="width:120px">创建时间</span>
        <span style="width:140px">操作</span>
      </div>
      <div v-for="(r, idx) in roles" :key="r.id" class="table-row">
        <span class="mono dim" style="width:40px">{{ String(idx + 1).padStart(2, '0') }}</span>
        <span class="mono accent" style="width:120px">{{ r.roleCode }}</span>
        <span style="flex:1">{{ r.roleName }}</span>
        <span class="mono dim" style="width:80px">{{ r.tenantId ?? '全局' }}</span>
        <span style="width:60px">
          <span class="tag" :class="r.enabled ? 'tag-success' : 'tag-danger'">{{ r.enabled ? '启用' : '禁用' }}</span>
        </span>
        <span class="mono dim" style="width:120px;font-size:11px">{{ r.createdAt?.slice(0, 10) || '—' }}</span>
        <span style="width:140px;display:inline-flex;gap:6px">
          <button v-permission="'omp:system:role:edit'" class="btn-mini" @click="handleEdit(r)">编辑</button>
          <button v-permission="'omp:system:role:assign-menu'" class="btn-mini" @click="handleMenus(r)">菜单</button>
          <button v-permission="'omp:system:role:delete'" class="btn-mini-danger" @click="handleDelete(r)">删除</button>
        </span>
      </div>
    </div>

    <RoleForm v-if="showForm" :role="editingRole" @close="showForm = false" @saved="onFormSaved" />
    <RoleMenuDialog v-if="showMenuDialog" :role-id="menuRoleId!" @close="showMenuDialog = false" @saved="onMenusSaved" />
  </div>
</template>
