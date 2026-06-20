<script setup lang="ts">
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { createMenu, updateMenu, type MenuInfo, type CreateMenuParams, type UpdateMenuParams } from '@/api/system'

const props = defineProps<{ menu: MenuInfo | null; parentOptions: MenuInfo[] }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const menuCode = ref(props.menu?.menuCode || '')
const menuName = ref(props.menu?.menuName || '')
const menuType = ref(props.menu?.menuType || 'menu')
const parentId = ref<number | null>(props.menu?.parentId ?? null)
const path = ref(props.menu?.path || '')
const component = ref(props.menu?.component || '')
const icon = ref(props.menu?.icon || '')
const sortOrder = ref(props.menu?.sortOrder ?? 0)
const permission = ref(props.menu?.permission || '')
const enabled = ref(props.menu?.enabled ?? true)
const editMode = !!(props.menu && props.menu.id)

/** 从树形结构展平所有父级可选项 */
function flattenParentOptions(items: MenuInfo[], depth = 0): { value: number; label: string; depth: number }[] {
  const result: { value: number; label: string; depth: number }[] = []
  for (const m of items) {
    if (editMode && m.id === props.menu?.id) continue
    result.push({ value: m.id, label: '—'.repeat(depth) + ' ' + m.menuName, depth })
    if (m.children) {
      result.push(...flattenParentOptions(m.children, depth + 1))
    }
  }
  return result
}
const parentCandidates = flattenParentOptions(props.parentOptions)

async function handleSubmit() {
  if (!menuCode.value) { msg.error('菜单编码不能为空'); return }
  if (!menuName.value) { msg.error('菜单名称不能为空'); return }
  loading.value = true
  try {
    if (editMode && props.menu) {
      const params: UpdateMenuParams = {
        menuName: menuName.value,
        menuType: menuType.value,
        parentId: parentId.value ?? undefined,
        path: path.value || undefined,
        component: component.value || undefined,
        icon: icon.value || undefined,
        sortOrder: sortOrder.value,
        permission: permission.value || undefined,
        enabled: enabled.value,
      }
      await updateMenu(props.menu.id, params)
      msg.success('已更新')
    } else {
      const params: CreateMenuParams = {
        menuCode: menuCode.value,
        menuName: menuName.value,
        menuType: menuType.value,
        parentId: parentId.value || undefined,
        path: path.value || undefined,
        component: component.value || undefined,
        icon: icon.value || undefined,
        sortOrder: sortOrder.value,
        permission: permission.value || undefined,
      }
      await createMenu(params)
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
          <h3>{{ editMode ? '编辑菜单' : '创建菜单' }}</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <form class="modal-body" @submit.prevent="handleSubmit">
          <label class="field">
            <span>菜单编码</span>
            <input v-model="menuCode" class="field-input" :disabled="editMode" placeholder="如 system:user:list" />
          </label>
          <label class="field">
            <span>菜单名称</span>
            <input v-model="menuName" class="field-input" placeholder="如 用户管理" />
          </label>
          <label class="field">
            <span>菜单类型</span>
            <select v-model="menuType" class="field-input">
              <option value="menu">菜单</option>
              <option value="button">按钮</option>
              <option value="api">接口</option>
            </select>
          </label>
          <label class="field">
            <span>上级菜单</span>
            <select v-model.number="parentId" class="field-input">
              <option :value="null">-- 无（顶级） --</option>
              <option v-for="p in parentCandidates" :key="p.value" :value="p.value">{{ p.label }}</option>
            </select>
          </label>
          <label class="field">
            <span>路由路径</span>
            <input v-model="path" class="field-input" placeholder="/system/user" />
          </label>
          <label class="field">
            <span>组件路径</span>
            <input v-model="component" class="field-input" placeholder="system/user/UserList.vue" />
          </label>
          <label class="field">
            <span>图标</span>
            <input v-model="icon" class="field-input" placeholder="user" />
          </label>
          <label class="field">
            <span>排序</span>
            <input v-model.number="sortOrder" class="field-input" type="number" />
          </label>
          <label class="field">
            <span>权限标识</span>
            <input v-model="permission" class="field-input" placeholder="system:user:list" />
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
  border-radius: var(--radius); width: 480px; max-height: 90vh;
  overflow-y: auto; box-shadow: 0 16px 48px rgba(0,0,0,0.12);
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 18px 24px; border-bottom: 1px solid var(--border);
}
.modal-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
.btn-close { background: none; border: 0; font-size: 16px; cursor: pointer; color: var(--ink-mute); }
.modal-body { display: flex; flex-direction: column; gap: 14px; padding: 20px 24px; }
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
