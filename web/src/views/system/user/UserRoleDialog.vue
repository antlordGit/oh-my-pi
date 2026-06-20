<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { listRoles, getUser, assignUserRoles, type RoleInfo } from '@/api/system'

const props = defineProps<{ userId: number }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const roles = ref<RoleInfo[]>([])
const selectedRoleIds = ref<Set<number>>(new Set())

async function load() {
  roles.value = await listRoles()
  const u = await getUser(props.userId)
  if (u.roleIds) {
    selectedRoleIds.value = new Set(u.roleIds)
  }
}

function toggle(roleId: number) {
  const s = new Set(selectedRoleIds.value)
  if (s.has(roleId)) s.delete(roleId); else s.add(roleId)
  selectedRoleIds.value = s
}

async function handleSave() {
  loading.value = true
  try {
    await assignUserRoles(props.userId, [...selectedRoleIds.value])
    msg.success('角色已分配')
    emit('saved')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '操作失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <Teleport to="body">
    <div class="modal-overlay" @click.self="emit('close')">
      <div class="modal-card">
        <header class="modal-header">
          <h3>分配角色</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <div class="modal-body">
          <p class="hint">为用户 <strong>{{ userId }}</strong> 选择角色</p>
          <div v-if="!roles.length" class="dim" style="text-align:center;padding:20px">暂无角色</div>
          <label v-for="r in roles" :key="r.id" class="check-item">
            <input
              type="checkbox"
              :checked="selectedRoleIds.has(r.id)"
              @change="toggle(r.id)"
            />
            <span class="check-label">
              <span class="mono accent">{{ r.roleCode }}</span>
              <span class="dim" style="margin-left:8px">{{ r.roleName }}</span>
            </span>
          </label>
          <footer class="modal-footer">
            <button type="button" class="btn-ghost" @click="emit('close')">取消</button>
            <button class="btn-primary" :disabled="loading" @click="handleSave">
              {{ loading ? '保存中...' : '保存' }}
            </button>
          </footer>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3);
  display: flex; align-items: center; justify-content: center;
  z-index: 100; backdrop-filter: blur(4px);
}
.modal-card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); width: 440px; max-height: 90vh;
  overflow-y: auto; box-shadow: 0 16px 48px rgba(0,0,0,0.12);
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 18px 24px; border-bottom: 1px solid var(--border);
}
.modal-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
.btn-close { background: none; border: 0; font-size: 16px; cursor: pointer; color: var(--ink-mute); }
.modal-body { display: flex; flex-direction: column; gap: 14px; padding: 20px 24px; }
.hint { font-size: 13px; color: var(--ink-mute); margin: 0; }
.hint strong { color: var(--brand); }
.check-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); cursor: pointer;
  transition: background var(--dur-fast);
}
.check-item:hover { background: var(--surface-hover); }
.check-label { font-size: 13px; }
.modal-footer { display: flex; gap: 10px; justify-content: flex-end; }
.btn-primary {
  padding: 8px 20px; border-radius: var(--radius-pill); border: 0;
  background: var(--brand); color: #fff; font-size: 13px; font-weight: 600;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-ghost {
  padding: 8px 20px; border-radius: var(--radius-pill); border: 1px solid var(--border);
  background: transparent; color: var(--ink-2); font-size: 13px; cursor: pointer;
}
</style>
