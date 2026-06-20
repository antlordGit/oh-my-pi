<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { listMenus, getRole, assignRoleMenus, type MenuInfo } from '@/api/system'

const props = defineProps<{ roleId: number }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const menus = ref<MenuInfo[]>([])
const selectedMenuIds = ref<Set<number>>(new Set())
const expanded = ref<Set<number>>(new Set([0]))
// 子菜单 id -> 父菜单 id，用于勾选时自动选中上级
const parentMap = ref<Map<number, number>>(new Map())

function buildParentMap(items: MenuInfo[], parentId: number | null = null) {
  for (const m of items) {
    if (parentId !== null) parentMap.value.set(m.id, parentId)
    if (m.children && m.children.length > 0) {
      buildParentMap(m.children, m.id)
    }
  }
}

function flattenMenus(items: MenuInfo[], level = 0): { item: MenuInfo; level: number }[] {
  const result: { item: MenuInfo; level: number }[] = []
  for (const m of items) {
    result.push({ item: m, level })
    if (m.children && m.children.length > 0 && expanded.value.has(m.id)) {
      result.push(...flattenMenus(m.children, level + 1))
    }
  }
  return result
}
const flatList = ref<{ item: MenuInfo; level: number }[]>([])

function rebuildFlatList() {
  flatList.value = flattenMenus(menus.value)
}

function toggleExpand(id: number) {
  const s = new Set(expanded.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  expanded.value = s
  rebuildFlatList()
}

function toggleMenu(menuId: number) {
  const s = new Set(selectedMenuIds.value)
  if (s.has(menuId)) {
    s.delete(menuId)
  } else {
    s.add(menuId)
    // 选中时自动勾选所有上级菜单（支持多级）
    let pid = parentMap.value.get(menuId)
    while (pid !== undefined) {
      s.add(pid)
      pid = parentMap.value.get(pid)
    }
  }
  selectedMenuIds.value = s
}

async function load() {
  menus.value = await listMenus(true)
  buildParentMap(menus.value)
  const role = await getRole(props.roleId)
  if (role.menuIds) {
    selectedMenuIds.value = new Set(role.menuIds)
  }
  // 默认展开根节点
  for (const m of menus.value) {
    expanded.value.add(m.id)
  }
  rebuildFlatList()
}

async function handleSave() {
  loading.value = true
  try {
    await assignRoleMenus(props.roleId, [...selectedMenuIds.value])
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
            <div
              v-for="{ item, level } in flatList"
              :key="item.id"
              class="tree-row"
              :style="{ paddingLeft: `${12 + level * 24}px` }"
            >
              <span
                v-if="item.children && item.children.length"
                class="expand-btn"
                @click="toggleExpand(item.id)"
              >{{ expanded.has(item.id) ? '▾' : '▸' }}</span>
              <span v-else class="expand-spacer"></span>
              <label class="check-item">
                <input
                  type="checkbox"
                  :checked="selectedMenuIds.has(item.id)"
                  @change="toggleMenu(item.id)"
                />
                <span class="check-label">
                  <span class="mono dim" style="font-size:11px">{{ item.menuCode }}</span>
                  <span style="margin-left:8px">{{ item.menuName }}</span>
                  <span class="tag tag-mute" style="margin-left:8px">{{ item.menuType }}</span>
                </span>
              </label>
            </div>
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
.menu-tree { display: flex; flex-direction: column; }
.tree-row { display: flex; align-items: center; }
.expand-btn {
  width: 20px; font-size: 11px; cursor: pointer; color: var(--ink-mute);
  text-align: center; flex-shrink: 0;
}
.expand-spacer { width: 20px; flex-shrink: 0; }
.check-item {
  display: flex; align-items: center; gap: 8px; padding: 6px 8px;
  border-radius: var(--radius-sm); cursor: pointer; flex: 1;
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
.tag-mute {
  display: inline-flex; align-items: center; padding: 1px 8px;
  border-radius: var(--radius-pill); font-size: 10px; font-weight: 500;
  background: var(--surface-soft); color: var(--ink-mute); border: 1px solid var(--border);
}
</style>
