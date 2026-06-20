<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { readFile, writeFile } from '@/api/repo'

interface Props {
  repoId: string | null | undefined
  /** Relative file path within the workspace. */
  filePath: string | null | undefined
  fileName: string
}

const props = defineProps<Props>()
const emit = defineEmits<{ close: [] }>()

const msg = useMessage()

const content = ref('')
const editing = ref('')
const dirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const showConfirm = ref(false)

watch(
  () => props.filePath,
  async (p) => {
    if (!p || !props.repoId) { content.value = ''; editing.value = ''; dirty.value = false; return }
    loading.value = true
    try {
      const r = await readFile(props.repoId, p)
      content.value = r.content
      editing.value = r.content
      dirty.value = false
    } catch (e: any) {
      content.value = ''
      editing.value = ''
      msg.error(e?.response?.data?.error || '读取失败')
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function onInput() { dirty.value = editing.value !== content.value }
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    if (dirty.value) showConfirm.value = true
  }
}

function cancelSave() { showConfirm.value = false }
async function confirmSave() {
  if (!props.repoId || !props.filePath) return
  showConfirm.value = false
  saving.value = true
  try {
    await writeFile(props.repoId, props.filePath, editing.value)
    content.value = editing.value
    dirty.value = false
    msg.success('文件已保存')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

function revert() {
  editing.value = content.value
  dirty.value = false
}

const lineCount = computed(() => editing.value.split('\n').length)
const charCount = computed(() => editing.value.length)
</script>

<template>
  <Transition name="slide-right">
    <aside v-if="props.filePath" class="file-editor">
      <!-- Toolbar -->
      <header class="fe-head">
        <div class="fe-head-left">
          <span class="fe-icon">▫</span>
          <span class="fe-name mono">{{ props.fileName }}</span>
          <span class="fe-meta mono dim">{{ lineCount }} 行 · {{ charCount }} 字符</span>
        </div>
        <div class="fe-head-right">
          <button
            class="fe-btn fe-btn-save"
            :disabled="!dirty || saving"
            @click="showConfirm = true"
          >{{ saving ? '保存中…' : '保存' }}</button>
          <button
            class="fe-btn fe-btn-revert"
            :disabled="!dirty"
            @click="revert"
          >撤销</button>
          <button class="fe-btn fe-btn-close" @click="emit('close')">✕</button>
        </div>
      </header>

      <!-- Editor -->
      <div class="fe-body">
        <div v-if="loading" class="fe-empty mono dim">加载中…</div>
        <textarea
          v-else
          v-model="editing"
          class="fe-textarea mono"
          spellcheck="false"
          @input="onInput"
          @keydown="onKeydown"
        ></textarea>
      </div>

      <!-- Save confirmation overlay -->
      <Transition name="fade">
        <div v-if="showConfirm" class="fe-overlay" @click.self="cancelSave">
          <div class="fe-confirm card">
            <h3 class="fe-confirm-title">确认保存</h3>
            <p class="fe-confirm-body">
              即将将当前编辑内容写入<br />
              <code class="mono">{{ props.filePath }}</code>
            </p>
            <div class="fe-confirm-act">
              <button class="btn-mini-danger" @click="cancelSave">取消</button>
              <button class="btn-primary" @click="confirmSave" :disabled="saving">
                {{ saving ? '保存中…' : '确认保存' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </aside>
  </Transition>
</template>

<style scoped>
/* ===================================================================
   File Editor — slide-in panel
   =================================================================== */
.file-editor {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: var(--surface, #FFFFFF);
  display: flex;
  flex-direction: column;
  border-radius: var(--radius, 12px);
  overflow: hidden;
}

/* ---- Toolbar ---- */
.fe-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border, #E5E6EB);
  background: var(--surface-soft, #F7F8FA);
  flex-shrink: 0;
}
.fe-head-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.fe-icon { color: var(--brand, #165DFF); font-size: 12px; }
.fe-name {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink, #1D2129);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
}
.fe-meta { font-size: 10px; margin-left: 6px; }

.fe-head-right { display: flex; align-items: center; gap: 6px; }
.fe-btn {
  border: 1px solid var(--border, #E5E6EB);
  border-radius: 4px;
  background: var(--surface, #FFFFFF);
  color: var(--ink-2, #4E5969);
  font-family: var(--font-mono);
  font-size: 10.5px;
  padding: 3px 10px;
  cursor: pointer;
  transition: all 100ms ease;
}
.fe-btn:hover:not(:disabled) { border-color: var(--brand, #165DFF); color: var(--brand, #165DFF); }
.fe-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.fe-btn-save { background: var(--brand, #165DFF); color: #fff; border-color: var(--brand, #165DFF); }
.fe-btn-save:hover:not(:disabled) { background: var(--brand-hover, #0E49D6); }
.fe-btn-close { font-size: 12px; padding: 3px 8px; }

/* ---- Body ---- */
.fe-body {
  flex: 1;
  overflow: hidden;
  display: flex;
}
.fe-empty {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
}

.fe-textarea {
  width: 100%;
  height: 100%;
  border: 0;
  outline: 0;
  padding: 12px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--ink, #1D2129);
  background: var(--surface, #FFFFFF);
  resize: none;
  tab-size: 2;
  white-space: pre;
  overflow-wrap: normal;
  overflow-x: auto;
}
.fe-textarea:focus { box-shadow: none; }

/* ---- Overlay ---- */
.fe-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
}
.fe-confirm {
  padding: 28px 32px;
  max-width: 320px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  text-align: center;
}
.fe-confirm-title {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: var(--ink, #1D2129);
  margin: 0;
}
.fe-confirm-body {
  font-size: 13px;
  color: var(--ink-2, #4E5969);
  line-height: 1.6;
  margin: 0;
}
.fe-confirm-body code {
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--brand-soft, rgba(22,93,255,0.08));
  color: var(--brand, #165DFF);
  padding: 1px 6px;
  border-radius: 4px;
  word-break: break-all;
}
.fe-confirm-act {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.fe-confirm-act .btn-mini-danger {
  padding: 6px 16px;
  font-size: 12px;
}
.fe-confirm-act .btn-primary {
  padding: 6px 20px;
  font-size: 12px;
}

/* ===================================================================
   Transitions
   =================================================================== */
.slide-right-enter-active,
.slide-right-leave-active {
  transition: transform 280ms cubic-bezier(0.22, 0.61, 0.36, 1), opacity 200ms ease;
}
.slide-right-enter-from,
.slide-right-leave-to {
  transform: translateX(20px);
  opacity: 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 160ms ease;
}
.fade-enter-from,
.fade-leave-to { opacity: 0; }
</style>