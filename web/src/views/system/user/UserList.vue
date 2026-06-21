<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { listUsers, deleteUser, type UserInfo } from '@/api/system'
import UserForm from './UserForm.vue'
import UserRoleDialog from './UserRoleDialog.vue'

const msg = useMessage()
const dialog = useDialog()
const refreshStats = inject<() => Promise<void>>('refreshStats')

const users = ref<UserInfo[]>([])
const showForm = ref(false)
const editingUser = ref<UserInfo | null>(null)
const showRoleDialog = ref(false)
const roleUserId = ref<number | null>(null)

async function load() {
  users.value = await listUsers()
}

function handleCreate() {
  editingUser.value = null
  showForm.value = true
}
function handleEdit(u: UserInfo) {
  editingUser.value = u
  showForm.value = true
}
function handleRoles(u: UserInfo) {
  roleUserId.value = u.id
  showRoleDialog.value = true
}
function handleDelete(u: UserInfo) {
  dialog.warning({
    title: '删除用户',
    content: `确认删除用户「${u.username}」？此操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteUser(u.id)
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
function onRolesSaved() {
  showRoleDialog.value = false
  load()
  refreshStats?.()
}

const levelLabels: Record<string, string> = {
  super_admin: '超级管理员',
  admin: '管理员',
  user: '普通用户',
}

onMounted(load)
</script>

<template>
  <div>
    <div class="header">
      <h2 class="title">用户管理</h2>
      <button v-permission="'omp:system:user:create'" class="btn-primary" @click="handleCreate">+ 创建用户</button>
    </div>

    <div v-if="!users.length" class="empty card">
      <span class="empty-icon">—</span>
      <p class="empty-text">暂无用户</p>
    </div>

    <div v-else class="card">
      <div class="table-head mono">
        <span style="width:40px">#</span>
        <span style="flex:1">用户名</span>
        <span style="width:120px">姓名</span>
        <span style="width:100px">身份级别</span>
        <span style="width:120px">租户</span>
        <span style="width:140px">角色</span>
        <span style="width:60px">状态</span>
        <span style="width:120px">磁盘(已用/限额)</span>
        <span style="width:140px">Token(已用/额度)</span>
        <span style="width:120px">创建时间</span>
        <span style="width:140px">操作</span>
      </div>
      <div v-for="(u, idx) in users" :key="u.id" class="table-row">
        <span class="mono dim" style="width:40px">{{ String(idx + 1).padStart(2, '0') }}</span>
        <span style="flex:1" class="mono accent">{{ u.username }}</span>
        <span style="width:120px" :title="u.name || ''">{{ u.name || '—' }}</span>
        <span style="width:100px">
          <span class="tag" :class="{
            'tag': u.identityLevel === 'super_admin',
            'tag-success': u.identityLevel === 'admin',
            'tag-mute': u.identityLevel === 'user'
          }">{{ levelLabels[u.identityLevel] || u.identityLevel }}</span>
        </span>
        <span style="width:120px" :title="u.tenantName || ''">{{ u.tenantName || '—' }}</span>
        <span style="width:140px" :title="u.roleNames?.join('、') || ''">{{ u.roleNames?.length ? u.roleNames.join('、') : '—' }}</span>
        <span style="width:60px">
          <span class="tag" :class="u.enabled ? 'tag-success' : 'tag-danger'">
            {{ u.enabled ? '启用' : '禁用' }}
          </span>
        </span>
        <span class="mono dim" style="width:120px;font-size:11px">
          <span :class="{ 'tag-danger': u.diskUsageMb >= u.diskLimitMb }">{{ u.diskUsageMb }}</span> / {{ u.diskLimitMb }} MB
        </span>
        <span class="mono dim" style="width:140px;font-size:11px">
          <template v-if="u.tokenLimit > 0">
            <span :class="{ 'tag-danger': u.tokenUsed >= u.tokenLimit }">{{ u.tokenUsed }}</span> / {{ u.tokenLimit }}
          </template>
          <template v-else>{{ u.tokenUsed }} / 不限</template>
        </span>
        <span class="mono dim" style="width:120px;font-size:11px">{{ u.createdAt?.slice(0, 10) || '—' }}</span>
        <span style="width:140px;display:inline-flex;gap:6px">
          <button v-permission="'omp:system:user:edit'" class="btn-mini" @click="handleEdit(u)">编辑</button>
          <button v-permission="'omp:system:user:assign-role'" class="btn-mini" @click="handleRoles(u)">角色</button>
          <button v-permission="'omp:system:user:delete'" class="btn-mini-danger" @click="handleDelete(u)">删除</button>
        </span>
      </div>
    </div>

    <!-- Dialogs -->
    <UserForm v-if="showForm" :user="editingUser" @close="showForm = false" @saved="onFormSaved" />
    <UserRoleDialog v-if="showRoleDialog" :user-id="roleUserId!" @close="showRoleDialog = false" @saved="onRolesSaved" />
  </div>
</template>
