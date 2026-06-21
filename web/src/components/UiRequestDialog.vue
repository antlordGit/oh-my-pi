<script setup lang="ts">
import { ref } from 'vue'

interface UiRequest {
  id: string
  method: 'select' | 'confirm' | 'input' | 'editor'
  title: string
  options?: string[]
  message?: string
  placeholder?: string
  prefill?: string
  timeout?: number
}

const props = defineProps<{ request: UiRequest }>()
const emit = defineEmits<{
  (e: 'submit', id: string, value: string | boolean): void
  (e: 'cancel', id: string): void
}>()

// select: 单选列表
const selectedOption = ref<string | null>(null)
// confirm: 确认对话框
const confirmed = ref<boolean | null>(null)
// input / editor: 文本输入
const inputValue = ref(props.request.prefill || '')

function submitSelect() {
  if (!selectedOption.value) return
  emit('submit', props.request.id, selectedOption.value)
}

function submitConfirm(value: boolean) {
  emit('submit', props.request.id, value)
}

function submitInput() {
  emit('submit', props.request.id, inputValue.value)
}

function submitEditor() {
  emit('submit', props.request.id, inputValue.value)
}

function cancel() {
  emit('cancel', props.request.id)
}
</script>

<template>
  <section class="ui-dialog card">
    <header class="ui-dialog-head mono">
      <span class="caret">◆</span>
      <span>{{ request.title || '请输入' }}</span>
      <span v-if="request.timeout" class="timeout-hint">({{ Math.round(request.timeout / 1000) }}s)</span>
    </header>

    <!-- select: 单选列表 -->
    <div v-if="request.method === 'select'" class="ui-dialog-body">
      <div class="options">
        <button
          v-for="(opt, i) in request.options"
          :key="'opt-' + i"
          type="button"
          class="option"
          :class="{ on: selectedOption === opt }"
          @click="selectedOption = opt"
        >
          <span class="marker is-radio"></span>
          <span class="option-label">{{ opt }}</span>
        </button>
      </div>
      <footer class="ui-dialog-foot">
        <button class="btn-ghost" @click="cancel">取消</button>
        <button class="btn-primary" :disabled="!selectedOption" @click="submitSelect">确认</button>
      </footer>
    </div>

    <!-- confirm: 确认对话框 -->
    <div v-else-if="request.method === 'confirm'" class="ui-dialog-body">
      <p class="confirm-message" v-if="request.message">{{ request.message }}</p>
      <div class="confirm-actions">
        <button class="btn-ghost btn-cancel" @click="submitConfirm(false)">否</button>
        <button class="btn-primary" @click="submitConfirm(true)">是</button>
      </div>
    </div>

    <!-- input: 单行输入 -->
    <div v-else-if="request.method === 'input'" class="ui-dialog-body">
      <input
        v-model="inputValue"
        class="field-raw"
        type="text"
        :placeholder="request.placeholder || '请输入'"
        @keydown.enter.prevent="submitInput"
      />
      <footer class="ui-dialog-foot">
        <button class="btn-ghost" @click="cancel">取消</button>
        <button class="btn-primary" :disabled="!inputValue.trim()" @click="submitInput">提交</button>
      </footer>
    </div>

    <!-- editor: 多行文本 -->
    <div v-else-if="request.method === 'editor'" class="ui-dialog-body">
      <textarea
        v-model="inputValue"
        class="field-raw mono"
        rows="6"
        :placeholder="request.placeholder || '请输入'"
        style="resize:vertical;font-size:12px"
      />
      <footer class="ui-dialog-foot">
        <button class="btn-ghost" @click="cancel">取消</button>
        <button class="btn-primary" @click="submitEditor">提交</button>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.ui-dialog {
  margin: 10px 0 4px;
  padding: 14px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-soft);
  animation: fade-up var(--dur-slow) var(--ease-out) both;
}
.ui-dialog-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink);
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px dashed var(--border);
}
.ui-dialog-head .caret { color: var(--brand); }
.timeout-hint { color: var(--ink-faint); font-size: 11px; margin-left: auto; }
.ui-dialog-body { display: flex; flex-direction: column; gap: 10px; }
.confirm-message { margin: 0 0 8px; color: var(--ink-2); font-size: 14px; }
.confirm-actions { display: flex; gap: 10px; justify-content: flex-end; }
.btn-cancel { color: var(--ink-mute); }

/* 单选列表 (复用 ClarifyForm 的 marker 样式) */
.options { display: flex; flex-wrap: wrap; gap: 8px; }
.option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 13px;
  font-size: 13px;
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.option:hover { border-color: var(--brand); color: var(--ink); }
.option.on {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-soft);
}
.marker {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  position: relative;
  transition: all var(--dur-fast) var(--ease-out);
}
.option.on .marker { border-color: var(--brand); background: var(--brand); }
.option.on .marker.is-radio::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: var(--ink-invert);
}

.ui-dialog-foot {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding-top: 10px;
  margin-top: 6px;
  border-top: 1px dashed var(--border);
}
.btn-primary { padding: 7px 18px; font-size: 13px; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost {
  padding: 7px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost:hover { border-color: var(--brand); color: var(--brand); }
</style>