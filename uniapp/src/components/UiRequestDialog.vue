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

const selectedOption = ref<string | null>(null)
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
  <view class="ui-dialog">
    <view class="ui-head">
      <text class="ui-title">{{ request.title || '请输入' }}</text>
      <text v-if="request.timeout" class="ui-timeout">
        {{ Math.round(request.timeout / 1000) }}s
      </text>
    </view>

    <!-- select -->
    <view v-if="request.method === 'select'" class="ui-body">
      <view class="options">
        <view
          v-for="(opt, i) in request.options"
          :key="'opt-' + i"
          :class="['option', { on: selectedOption === opt }]"
          @click="selectedOption = opt"
        >
          <view :class="['rd', { on: selectedOption === opt }]" />
          <text class="option-text">{{ opt }}</text>
        </view>
      </view>

      <view class="ui-foot">
        <view class="btn ghost" @click="cancel">取消</view>
        <view
          :class="['btn primary', { disabled: !selectedOption }]"
          @click="submitSelect"
        >确认</view>
      </view>
    </view>

    <!-- confirm -->
    <view v-else-if="request.method === 'confirm'" class="ui-body">
      <text v-if="request.message" class="confirm-msg">
        {{ request.message }}
      </text>
      <view class="ui-foot">
        <view class="btn ghost" @click="submitConfirm(false)">否</view>
        <view class="btn primary" @click="submitConfirm(true)">是</view>
      </view>
    </view>

    <!-- input -->
    <view v-else-if="request.method === 'input'" class="ui-body">
      <input
        v-model="inputValue"
        class="dialog-input"
        :placeholder="request.placeholder || '请输入'"
        placeholder-class="ph"
        confirm-type="done"
        @confirm="submitInput"
      />
      <view class="ui-foot">
        <view class="btn ghost" @click="cancel">取消</view>
        <view
          :class="['btn primary', { disabled: !inputValue.trim() }]"
          @click="submitInput"
        >提交</view>
      </view>
    </view>

    <!-- editor -->
    <view v-else-if="request.method === 'editor'" class="ui-body">
      <textarea
        v-model="inputValue"
        class="dialog-textarea"
        :placeholder="request.placeholder || '请输入'"
        placeholder-class="ph"
        :auto-height="true"
        :maxlength="-1"
      />
      <view class="ui-foot">
        <view class="btn ghost" @click="cancel">取消</view>
        <view class="btn primary" @click="submitEditor">提交</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.ui-dialog {
  padding: 12px 14px;
  border-radius: var(--radius-bubble);
}

.ui-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 10px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--separator);
}

.ui-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.ui-timeout {
  font-size: 11px;
  color: var(--ink-faint);
}

.ui-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.confirm-msg {
  font-size: 14px;
  color: var(--ink-2);
  line-height: 1.5;
  margin-bottom: 4px;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--surface-soft);
  border-radius: 6px;
}

.option.on {
  background: var(--brand-soft);
}

.rd {
  width: 16px;
  height: 16px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  flex-shrink: 0;
}

.rd.on {
  border-color: var(--brand);
  background: var(--brand);
}

.option-text {
  font-size: 14px;
  color: var(--ink);
}

.option.on .option-text {
  color: var(--brand);
}

.ui-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding-top: 8px;
  margin-top: 4px;
  border-top: 1px solid var(--separator);
}

.btn {
  display: inline-flex;
  align-items: center;
  padding: 6px 16px;
  border-radius: 4px;
  font-size: 13px;
}

.btn.ghost {
  border: 1px solid var(--border);
  color: var(--ink-2);
  background: var(--surface);
}

.btn.primary {
  background: var(--brand);
  color: #fff;
}

.btn.primary.disabled {
  opacity: 0.45;
}

.dialog-input {
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink);
  font-size: 14px;
}

.dialog-textarea {
  min-height: 100px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink);
  font-size: 13px;
  font-family: var(--font-mono);
  width: 100%;
}

.ph {
  color: var(--ink-faint);
}
</style>