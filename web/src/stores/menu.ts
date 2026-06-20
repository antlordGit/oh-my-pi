import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getMyMenus, type MenuInfo } from '@/api/system'

export const useMenuStore = defineStore('menu', () => {
  const menus = ref<MenuInfo[]>([])
  const loaded = ref(false)

  async function loadMenus() {
    try {
      menus.value = await getMyMenus()
      loaded.value = true
    } catch {
      menus.value = []
    }
  }

  function clearMenus() {
    menus.value = []
    loaded.value = false
  }

  // 顶级菜单（后端返回的就是树形结构，顶层即根节点；仅取 menu 类型且启用）
  const topMenus = computed(() =>
    menus.value
      .filter(m => m.menuType === 'menu' && m.enabled)
      .sort((a, b) => a.sortOrder - b.sortOrder)
  )

  // 用户有权访问的路由 path 集合（递归收集所有菜单的 path，去掉 query 部分）
  const allowedPaths = computed(() => {
    const set = new Set<string>()
    const walk = (items: MenuInfo[]) => {
      for (const m of items) {
        if (m.path) set.add(m.path.split('?')[0])
        if (m.children?.length) walk(m.children)
      }
    }
    walk(menus.value)
    return set
  })

  /** 判断某个路由 path 是否在用户的菜单权限内。 */
  function canAccess(path: string): boolean {
    const base = path.split('?')[0]
    // 精确匹配，或命中某个允许 path 的子路径（如 /sessions/:id 命中 /sessions）
    if (allowedPaths.value.has(base)) return true
    for (const p of allowedPaths.value) {
      if (p !== '/' && base.startsWith(p + '/')) return true
    }
    return false
  }

  return { menus, loaded, loadMenus, clearMenus, topMenus, allowedPaths, canAccess }
})
