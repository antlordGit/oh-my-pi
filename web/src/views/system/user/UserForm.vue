<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { createUser, updateUser, type UserInfo, type CreateUserParams, type UpdateUserParams } from '@/api/system'

const props = defineProps<{ user: UserInfo | null }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()
const dialog = useDialog()

const loading = ref(false)
const username = ref(props.user?.username || '')
const name = ref(props.user?.name || '')
const password = ref('')
const identityLevel = ref(props.user?.identityLevel || 'user')
const diskLimitMb = ref(props.user?.diskLimitMb ?? 100)
const tokenLimit = ref(props.user?.tokenLimit ?? 0)
const editMode = !!props.user

/** 用户名规则：英文/数字/中划线/下划线，1-32 字符。仅用于登录。 */
const USERNAME_PATTERN = /^[A-Za-z0-9_-]{1,32}$/

async function handleSubmit() {
  if (!username.value) { msg.error('用户名不能为空'); return }
  if (!editMode && !USERNAME_PATTERN.test(username.value)) {
    msg.error('用户名只能包含英文、数字、中划线和下划线，且不超过 32 个字符'); return
  }
  if (!editMode && !password.value) { msg.error('密码不能为空'); return }
  loading.value = true
  try {
    if (editMode && props.user) {
      const params: UpdateUserParams = {
        name: name.value || undefined,
        identityLevel: identityLevel.value,
        diskLimitMb: diskLimitMb.value,
        tokenLimit: tokenLimit.value,
      }
      await updateUser(props.user.id, params)
      msg.success('已更新')
    } else {
      const params: CreateUserParams = {
        username: username.value,
        name: name.value || undefined,
        password: password.value,
        identityLevel: identityLevel.value,
        diskLimitMb: diskLimitMb.value,
        tokenLimit: tokenLimit.value,
      }
      await createUser(params)
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
          <h3>{{ editMode ? '编辑用户' : '创建用户' }}</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <form class="modal-body" @submit.prevent="handleSubmit">
          <label class="field">
            <span>用户名 <small style="color:var(--ink-mute);font-weight:normal">（登录用，创建后不可改）</small></span>
            <input v-model="username" class="field-input" :disabled="editMode" maxlength="32" placeholder="英文、数字、中划线或下划线，最多 32 字符" />
          </label>
          <label class="field">
            <span>姓名 <small style="color:var(--ink-mute);font-weight:normal">（展示用）</small></span>
            <input v-model="name" class="field-input" maxlength="64" placeholder="请输入姓名" />
          </label>
          <label class="field">
            <span>密码</span>
            <input v-model="password" class="field-input" type="password" :placeholder="editMode ? '留空则不修改' : '请输入密码'" />
          </label>
          <label class="field">
            <span>身份级别</span>
            <select v-model="identityLevel" class="field-input">
              <option value="user">普通用户</option>
              <option value="admin">管理员</option>
              <option value="super_admin">超级管理员</option>
            </select>
          </label>
          <label class="field">
            <span>磁盘限额 <small style="color:var(--ink-mute);font-weight:normal">（MB，默认 100）</small></span>
            <input v-model.number="diskLimitMb" class="field-input" type="number" min="1" placeholder="磁盘空间限额（MB）" />
          </label>
          <label class="field">
            <span>Token 额度 <small style="color:var(--ink-mute);font-weight:normal">（0 表示不限）</small></span>
            <input v-model.number="tokenLimit" class="field-input" type="number" min="0" placeholder="大模型 Token 额度上限" />
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
.field-input {
  padding: 8px 12px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); font-size: 13px;
  background: var(--surface); color: var(--ink);
  outline: none; transition: border-color var(--dur-fast);
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
