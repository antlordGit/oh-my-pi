<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { ElTree } from 'element-plus'
import { listMenus, getRole, assignRoleMenus, type MenuInfo } from '@/api/system'

const props = defineProps<{ roleId: number }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const menus = ref<MenuInfo[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()
// 角色已有的菜单 id（用于初始化勾选）
const checkedKeys = ref<number[]>([])

const treeProps = { children: 'children', label: 'menuName' }

/** 收集所有叶子节点 id（无子节点的菜单）。 */
function collectLeafIds(items: MenuInfo[], acc: Set<number>) {
  for (const m of items) {
    if (m.children && m.children.length > 0) {
      collectLeafIds(m.children, acc)
    } else {
      acc.add(m.id)
    }
  }
}

async function load() {
  menus.value = await listMenus(true)
  const role = await getRole(props.roleId)
  const stored = new Set(role.menuIds ?? [])
  // el-tree 非严格模式下传入父节点会级联勾选全部子节点，导致过度选中；
  // 只用已存储的叶子节点初始化，父节点的全选/半选状态由 el-tree 自动推算。
  const leafIds = new Set<number>()
  collectLeafIds(menus.value, leafIds)
  checkedKeys.value = [...stored].filter((id) => leafIds.has(id))
}

async function handleSave() {
  if (!treeRef.value) return
  loading.value = true
  try {
    // 全选节点 + 半选父节点：父节点处于半选时也需持久化，否则上级菜单会丢失
    const checked = treeRef.value.getCheckedKeys(false) as number[]
    const halfChecked = treeRef.value.getHalfCheckedKeys() as number[]
    const menuIds = [...new Set([...checked, ...halfChecked])]
    await assignRoleMenus(props.roleId, menuIds)
    msg.success('菜单权限已配置')
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
      <div class="modal-card modal-wide">
        <header class="modal-header">
          <h3>配置菜单权限</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <div class="modal-body">
          <p class="hint">为角色 <strong>{{ roleId }}</strong> 选择可访问的菜单</p>
          <div v-if="!menus.length" class="dim" style="text-align:center;padding:20px">暂无菜单</div>
          <div v-else class="menu-tree">
            <el-tree
              ref="treeRef"
              :data="menus"
              :props="treeProps"
              node-key="id"
              show-checkbox
              default-expand-all
              :default-checked-keys="checkedKeys"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span class="mono dim" style="font-size:11px">{{ data.menuCode }}</span>
                  <span style="margin-left:8px">{{ data.menuName }}</span>
                  <span class="tag tag-mute" style="margin-left:8px">{{ data.menuType }}</span>
                </span>
              </template>
            </el-tree>
          </div>
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
.modal-wide { width: 560px; }
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3);
  display: flex; align-items: center; justify-content: center;
  z-index: 100; backdrop-filter: blur(4px);
}
.modal-card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); max-height: 90vh;
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
.menu-tree { max-height: 420px; overflow-y: auto; }
.tree-node { display: inline-flex; align-items: center; font-size: 13px; }
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
.tag-mute {
  display: inline-flex; align-items: center; padding: 1px 8px;
  border-radius: var(--radius-pill); font-size: 10px; font-weight: 500;
  background: var(--surface-soft); color: var(--ink-mute); border: 1px solid var(--border);
}
</style>
