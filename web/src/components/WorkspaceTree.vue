<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getWorkspaceTree, type WorkspaceNode } from '@/api/workspace'
import FileEditor from '@/components/FileEditor.vue'

interface Props {
  repoId: string | null | undefined
}

const props = defineProps<Props>()

const tree = ref<WorkspaceNode[]>([])
const expanded = ref<Set<string>>(new Set())
const loading = ref(false)
const error = ref<string | null>(null)
const selectedPath = ref<string | null>(null)
const editingFile = ref<{ path: string; name: string } | null>(null)

// Directory that is currently "active" in the visual sense (last clicked directory)
const activeDir = ref<string | null>(null)

const FETCH_DEPTH = 8

async function load() {
  if (!props.repoId) { tree.value = []; return }
  loading.value = true
  error.value = null
  try {
    tree.value = await getWorkspaceTree(props.repoId, FETCH_DEPTH)
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || '加载失败'
    tree.value = []
  } finally { loading.value = false }
}

function toggle(node: WorkspaceNode) {
  if (node.type !== 'dir') {
    editingFile.value = { path: node.path, name: node.name }
    selectedPath.value = node.path
    return
  }
  activeDir.value = node.path
  if (expanded.value.has(node.path)) expanded.value.delete(node.path)
  else expanded.value.add(node.path)
  expanded.value = new Set(expanded.value)
}

const hasChildren = computed(() => tree.value.length > 0)

onMounted(load)
watch(() => props.repoId, () => { expanded.value = new Set(); load() })

// ---- row model -----------------------------------------------------------
interface Row {
  node: WorkspaceNode
  depth: number
  /** Position (px from left) of each ancestor's vertical guide line. */
  guides: number[]
  /** Whether this is the last child of its parent. */
  last: boolean
}

const GUTTER = 16  // px indent per level
const BASE   = 8   // px base left gutter

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const walk = (nodes: WorkspaceNode[], depth: number, ancestors: number[]) => {
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i]
      const last = i === nodes.length - 1
      out.push({ node: n, depth, guides: [...ancestors], last })
      if (n.type === 'dir' && expanded.value.has(n.path) && n.children?.length) {
        walk(n.children, depth + 1, [...ancestors, BASE + depth * GUTTER])
      }
    }
  }
  walk(tree.value, 0, [])
  return out
})

async function refresh() { expanded.value = new Set(); await load() }

// ---- icon helpers (unchanged) --------------------------------------------
function fileIcon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  switch (ext) {
    case 'json': case 'yaml': case 'yml': case 'toml': case 'xml': case 'ini': case 'conf': case 'config': case 'env': case 'dotenv': return '◇'
    case 'js': case 'mjs': case 'cjs': case 'ts': case 'tsx': case 'jsx': return 'λ'
    case 'vue': case 'svelte': return '◈'
    case 'css': case 'scss': case 'sass': case 'less': return '◐'
    case 'html': case 'htm': return '◉'
    case 'md': case 'markdown': return '▽'
    case 'py': case 'pyw': return 'π'
    case 'java': return '⋆'
    case 'kt': case 'kts': return '⋆'
    case 'go': return 'η'
    case 'rs': return 'τ'
    case 'c': case 'cpp': case 'cc': case 'cxx': case 'h': case 'hpp': return 'μ'
    case 'sh': case 'bash': case 'zsh': case 'fish': return 'ζ'
    case 'sql': case 'pgsql': return 'δ'
    case 'lock': case 'cargo': case 'gradle': return '⟐'
    case 'png': case 'jpg': case 'jpeg': case 'gif': case 'webp': case 'svg': case 'ico': return '▣'
    case 'woff': case 'woff2': case 'ttf': case 'otf': case 'eot': return 'Δ'
    case 'mp3': case 'wav': case 'ogg': case 'flac': return '♪'
    case 'mp4': case 'webm': case 'mov': case 'avi': return '▶'
    case 'zip': case 'tar': case 'gz': case 'bz2': case 'xz': case 'rar': case '7z': return '◐'
    case 'txt': case 'log': return '▭'
    case 'pdf': return '◆'
    case 'doc': case 'docx': return '◆'
    case 'gitignore': case 'gitattributes': case 'gitmodules': return '○'
    case 'license': case 'licence': return '✦'
    case 'readme': return '▽'
    default: return '▫'
  }
}

function iconClass(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  if (['json', 'yaml', 'yml', 'toml', 'xml', 'ini', 'conf', 'config', 'env'].includes(ext)) return 't-ico-data'
  if (['js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'vue', 'svelte'].includes(ext)) return 't-ico-code'
  if (['css', 'scss', 'sass', 'less'].includes(ext)) return 't-ico-style'
  if (['html', 'htm', 'md', 'markdown'].includes(ext)) return 't-ico-markup'
  if (['py', 'pyw', 'java', 'kt', 'kts', 'go', 'rs', 'c', 'cpp', 'h', 'hpp', 'sh', 'bash', 'sql'].includes(ext)) return 't-ico-code'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico'].includes(ext)) return 't-ico-img'
  if (['lock', 'cargo', 'gradle'].includes(ext)) return 't-ico-lock'
  if (['txt', 'log', 'pdf'].includes(ext)) return 't-ico-doc'
  return 't-ico-file'
}

defineExpose({ refresh })
</script>

<template>
  <aside class="ws-tree">
    <header class="ws-head">
      <span class="ws-head-mark">◈</span>
      <span>文件</span>
      <span class="ws-head-repo">{{ props.repoId || '—' }}</span>
      <button class="ws-refresh" :disabled="loading || !props.repoId" @click="refresh" title="刷新">↻</button>
    </header>

    <div class="ws-body">
      <div v-if="!props.repoId" class="ws-empty">暂未关联仓库</div>
      <div v-else-if="loading && !hasChildren" class="ws-empty">加载中…</div>
      <div v-else-if="error" class="ws-empty ws-err">{{ error }}</div>
      <div v-else-if="!hasChildren" class="ws-empty">暂无文件</div>

      <ul v-else class="t-list">
        <li
          v-for="row in rows"
          :key="row.node.path"
          class="t-row"
          :class="{ 'is-sel': selectedPath === row.node.path, 'is-dir': row.node.type === 'dir', 'is-last': row.last }"
          @click="toggle(row.node)"
        >
          <!-- Vertical guide lines — one per ancestor -->
          <span
            v-for="x in row.guides"
            :key="'g'+x"
            class="t-guide"
            :style="{ left: x + 'px' }"
          ></span>

          <!-- Horizontal connector arm for non-root entries -->
          <span
            v-if="row.depth > 0"
            class="t-arm"
            :style="{ left: (row.guides[row.depth - 1] ?? 0) + 'px' }"
          ></span>

          <span
            class="t-icon"
            :style="{ marginLeft: (row.depth * GUTTER) + 'px' }"
            :class="row.node.type === 'dir' ? 't-ico-dir' : iconClass(row.node.name)"
          >
            {{ row.node.type === 'dir' ? '▤' : fileIcon(row.node.name) }}
          </span>
          <span class="t-name">{{ row.node.name }}</span>
        </li>
      </ul>

      <!-- File Editor overlay (slides in from right edge) -->
      <FileEditor
        v-if="editingFile"
        :repo-id="props.repoId"
        :file-path="editingFile.path"
        :file-name="editingFile.name"
        @close="editingFile = null"
      />
    </div>
  </aside>
</template>

<style scoped>
/* ===================================================================
   Workspace Tree — Editorial Codex
   Tree guide lines · hover accent bar · selected highlight
   =================================================================== */

/* -- tokens ------------------------------------------------------------ */
.ws-tree {
  --t-gutter: 16px;
  --t-row-h:  27px;
  --t-base:   10px;
  --t-line:   var(--border, #E5E6EB);
  --t-fg:     var(--ink-2, #4E5969);
  --t-dim:    var(--ink-faint, #C9CDD4);
  --t-accent: var(--brand, #165DFF);
  --t-soft:   var(--brand-soft, rgba(22,93,255,0.08));
  --t-bg:     var(--surface, #FFFFFF);
  --t-bg2:    var(--surface-soft, #F7F8FA);
  --t-r:      var(--radius, 12px);

  grid-column: 3;
  grid-row: 2;
  position: relative;
  border: 1px solid var(--t-line);
  border-radius: var(--t-r);
  background: var(--t-bg);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  overflow: hidden; /* FileEditor slides inside this boundary */
  align-self: start;
  top: 84px;
  max-height: calc(100dvh - 110px);
  font-family: var(--font-mono);
  font-size: 11.5px;
  user-select: none;
  position: sticky;
}

/* -- header ------------------------------------------------------------ */
.ws-head {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--t-line);
  background: var(--t-bg2);
  font-weight: 600;
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--t-fg);
  text-transform: uppercase;
}
.ws-head-mark { color: var(--t-accent); font-size: 13px; }
.ws-head-repo {
  margin-left: auto;
  color: var(--t-dim);
  font-weight: 400;
  font-size: 10px;
  letter-spacing: 0;
  text-transform: none;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* -- body scroll ------------------------------------------------------- */
.ws-body {
  overflow-y: auto;
  overflow-x: hidden;
  flex: 1;
  min-height: 0;
  padding: 4px 0;
}

/* -- empty ------------------------------------------------------------- */
.ws-empty {
  padding: 28px 12px;
  font-size: 10.5px;
  color: var(--t-dim);
  text-align: center;
  letter-spacing: 0.03em;
}
.ws-err { color: var(--danger, #F53F3F); }

/* ===================================================================
   List & rows
   =================================================================== */
.t-list { list-style: none; margin: 0; padding: 0; }

.t-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 5px;
  height: var(--t-row-h);
  padding-right: 10px;
  color: var(--t-fg);
  cursor: pointer;
}

/* Hover accent stripe */
.t-row::before {
  content: '';
  position: absolute;
  inset: 2px 0 2px 0;
  border-radius: 0 3px 3px 0;
  width: 3px;
  background: transparent;
  transition: background 120ms ease;
}
.t-row:hover { background: var(--t-soft); }
.t-row:hover::before { background: var(--t-accent); }

/* Selected */
.t-row.is-sel {
  background: var(--t-accent);
  color: #fff;
}
.t-row.is-sel::before { background: rgba(255,255,255,0.3); }

/* ===================================================================
   Guide lines (vertical)
   =================================================================== */
.t-guide {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  pointer-events: none;
  background: var(--t-dim);
  opacity: 0.22;
}

/* Replace last child's vertical guide so it doesn't
   extend below the horizontal arm, regardless of parent depth */
.t-row.is-last > .t-guide:last-child {
  bottom: 50%;
}

/* ===================================================================
   Horizontal arm (⊢– connector from guide to icon)
   =================================================================== */
.t-arm {
  position: absolute;
  top: 50%;
  width: 10px;
  height: 1px;
  pointer-events: none;
  background: var(--t-dim);
  opacity: 0.22;
}

/* ===================================================================
   Icon
   =================================================================== */
.t-icon {
  width: 13px;
  text-align: center;
  font-size: 11px;
  flex-shrink: 0;
}

.t-ico-dir   { color: var(--t-accent); font-size: 12px; }
.t-ico-code  { color: var(--t-accent); }
.t-ico-data  { color: var(--warn, #FF7D00); }
.t-ico-style { color: var(--showcase-purple, #7B7BFF); }
.t-ico-markup{ color: var(--good, #00B42A); }
.t-ico-img   { color: #A87DE8; }
.t-ico-lock  { color: var(--t-dim); }
.t-ico-doc   { color: var(--t-fg); }
.t-ico-file  { color: var(--t-dim); }

.t-row.is-sel .t-icon { color: rgba(255,255,255,0.88); }

/* ===================================================================
   Name
   =================================================================== */
.t-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  letter-spacing: 0.01em;
}
</style>