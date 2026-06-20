<script setup lang="ts">
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { createTenant, updateTenant, type TenantInfo, type CreateTenantParams, type UpdateTenantParams } from '@/api/system'

const props = defineProps<{ tenant: TenantInfo | null }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const tenantCode = ref(props.tenant?.tenantCode || '')
const tenantName = ref(props.tenant?.tenantName || '')
const description = ref(props.tenant?.description || '')
const enabled = ref(props.tenant?.enabled ?? true)
const editMode = !!props.tenant

async function handleSubmit() {
  if (!tenantCode.value) { msg.error('租户编码不能为空'); return }
  loading.value = true
  try {
    if (editMode && props.tenant) {
      const params: UpdateTenantParams = {
        tenantName: tenantName.value,
        description: description.value,
        enabled: enabled.value,
      }
      await updateTenant(props.tenant.id, params)
      msg.success('已更新')
    } else {
      const params: CreateTenantParams = {
        tenantCode: tenantCode.value,
        tenantName: tenantName.value || undefined,
        description: description.value || undefined,
      }
      await createTenant(params)
      msg.success('已创建')
    }
    emit('saved')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '操作失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-overlay" @click.self="emit('close')">
      <div class="modal-card">
        <header class="modal-header">
          <h3>{{ editMode ? '编辑租户' : '创建租户' }}</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <form class="modal-body" @submit.prevent="handleSubmit">
          <label class="field">
            <span>租户编码</span>
            <input v-model="tenantCode" class="field-input" :disabled="editMode" placeholder="唯一标识" />
          </label>
          <label class="field">
            <span>租户名称</span>
            <input v-model="tenantName" class="field-input" placeholder="显示名称" />
          </label>
          <label class="field">
            <span>描述</span>
            <textarea v-model="description" class="field-input" rows="3" placeholder="可选描述" />
          </label>
          <label class="field-check" v-if="editMode">
            <input type="checkbox" v-model="enabled" />
            <span>启用</span>
          </label>
          <footer class="modal-footer">
            <button type="button" class="btn-ghost" @click="emit('close')">取消</button>
            <button type="submit" class="btn-primary" :disabled="loading">
              {{ loading ? '提交中...' : (editMode ? '保存' : '创建') }}
            </button>
          </footer>
        </form>
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
.modal-body { display: flex; flex-direction: column; gap: 16px; padding: 20px 24px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field span { font-size: 12px; font-weight: 500; color: var(--ink-2); }
.field-check { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink-2); cursor: pointer; }
.field-input {
  padding: 8px 12px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); font-size: 13px;
  background: var(--surface); color: var(--ink);
  outline: none; transition: border-color var(--dur-fast);
  font-family: inherit;
}
.field-input:focus { border-color: var(--brand); }
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
