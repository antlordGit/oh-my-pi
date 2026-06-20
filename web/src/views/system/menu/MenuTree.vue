<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { listMenus, deleteMenu, type MenuInfo } from '@/api/system'
import MenuForm from './MenuForm.vue'

const msg = useMessage()
const dialog = useDialog()
const refreshStats = inject<() => Promise<void>>('refreshStats')

const menus = ref<MenuInfo[]>([])
const showForm = ref(false)
const editingMenu = ref<MenuInfo | null>(null)
const expanded = ref<Set<number>>(new Set([0]))

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

async function load() {
  menus.value = await listMenus(true)
  for (const m of menus.value) {
    expanded.value.add(m.id)
  }
  rebuildFlatList()
}

function handleCreate(parentId?: number) {
  editingMenu.value = null
  if (parentId !== undefined) {
    editingMenu.value = { parentId } as any
  }
  showForm.value = true
}
function handleEdit(m: MenuInfo) {
  editingMenu.value = m
  showForm.value = true
}
function handleDelete(m: MenuInfo) {
  dialog.warning({
    title: '删除菜单',
    content: `确认删除菜单「${m.menuName}」？子菜单将被保留。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteMenu(m.id)
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

const menuTypeLabels: Record<string, string> = {
  menu: '菜单',
  button: '按钮',
  api: '接口',
}

onMounted(load)
</script>

<template>
  <div>
    <div class="header">
      <h2 class="title">菜单管理</h2>
      <button v-permission="'omp:system:menu:create'" class="btn-primary" @click="handleCreate()">+ 创建菜单</button>
    </div>

    <div v-if="!menus.length" class="empty card">
      <span class="empty-icon">—</span>
      <p class="empty-text">暂无菜单</p>
    </div>

    <div v-else class="card">
      <div class="table-head mono">
        <span style="width:40px">#</span>
        <span style="flex:1">菜单名称</span>
        <span style="width:120px">菜单编码</span>
        <span style="width:70px">类型</span>
        <span style="width:70px">排序</span>
        <span style="width:180px">权限标识</span>
        <span style="width:60px">状态</span>
        <span style="width:120px">操作</span>
      </div>
      <div v-for="{ item, level } in flatList" :key="item.id" class="table-row">
        <span class="mono dim" style="width:40px;flex-shrink:0">{{ String(item.sortOrder) }}</span>
        <span style="flex:1;display:inline-flex;align-items:center">
          <span
            v-if="item.children && item.children.length"
            class="expand-btn"
            @click="toggleExpand(item.id)"
          >{{ expanded.has(item.id) ? '▾' : '▸' }}</span>
          <span v-else style="width:18px;flex-shrink:0"></span>
          <span class="tree-indent" :style="{ width: `${level * 20}px` }"></span>
          <span class="mono accent">{{ item.menuName }}</span>
          <span v-if="item.path" class="dim" style="font-size:10px;margin-left:8px">{{ item.path }}</span>
        </span>
        <span class="mono dim" style="width:120px;font-size:11px">{{ item.menuCode }}</span>
        <span style="width:70px">
          <span class="tag tag-mute">{{ menuTypeLabels[item.menuType] || item.menuType }}</span>
        </span>
        <span class="mono dim" style="width:70px">{{ item.sortOrder }}</span>
        <span class="mono dim" style="width:180px;font-size:10px">{{ item.permission || '—' }}</span>
        <span style="width:60px">
          <span class="tag" :class="item.enabled ? 'tag-success' : 'tag-danger'">{{ item.enabled ? '启用' : '禁用' }}</span>
        </span>
        <span style="width:120px;display:inline-flex;gap:6px">
          <button v-permission="'omp:system:menu:create'" class="btn-mini" @click="handleCreate(item.id)">子项</button>
          <button v-permission="'omp:system:menu:edit'" class="btn-mini" @click="handleEdit(item)">编辑</button>
          <button v-permission="'omp:system:menu:delete'" class="btn-mini-danger" @click="handleDelete(item)">删除</button>
        </span>
      </div>
    </div>

    <MenuForm v-if="showForm" :menu="editingMenu" :parent-options="menus" @close="showForm = false" @saved="onFormSaved" />
  </div>
</template>
