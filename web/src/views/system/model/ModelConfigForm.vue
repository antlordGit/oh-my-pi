<script setup lang="ts">
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { createModelConfig, updateModelConfig, type ModelConfigInfo, type CreateModelConfigParams, type UpdateModelConfigParams } from '@/api/system'

const props = defineProps<{ config: ModelConfigInfo | null }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const msg = useMessage()

const loading = ref(false)
const configName = ref(props.config?.configName || '')
const displayName = ref(props.config?.displayName || '')
const provider = ref(props.config?.provider || '')
const modelId = ref(props.config?.modelId || '')
const baseUrl = ref(props.config?.baseUrl || '')
const api = ref(props.config?.api ?? 'openai-completions')
const apiKey = ref(props.config?.apiKey || '')
const configJson = ref(props.config?.configJson || '')
const remark = ref(props.config?.remark || '')
const editMode = !!props.config
const showApiKey = ref(false)

/** OMP 支持的 API 协议（对应 catalog 的 KnownApi）。 */
const API_OPTIONS = [
  'openai-completions',
  'openai-responses',
  'openai-codex-responses',
  'azure-openai-responses',
  'anthropic-messages',
  'bedrock-converse-stream',
  'google-generative-ai',
  'google-gemini-cli',
  'google-vertex',
  'ollama-chat',
  'cursor-agent',
]

/** 清空表单（编辑模式下保留配置名称，因为它不可改）。 */
function handleReset() {
  if (!editMode) configName.value = ''
  displayName.value = ''
  provider.value = ''
  modelId.value = ''
  baseUrl.value = ''
  api.value = ''
  apiKey.value = ''
  configJson.value = ''
  remark.value = ''
}

async function handleSubmit() {
  if (!configName.value) { msg.error('配置名称不能为空'); return }
  if (!provider.value) { msg.error('provider 不能为空'); return }
  if (!modelId.value) { msg.error('modelId 不能为空'); return }
  loading.value = true
  try {
    if (editMode && props.config) {
      const params: UpdateModelConfigParams = {
        configName: configName.value,
        displayName: displayName.value || undefined,
        provider: provider.value,
        modelId: modelId.value,
        baseUrl: baseUrl.value || undefined,
        api: api.value || undefined,
        apiKey: apiKey.value || undefined,
        configJson: configJson.value || undefined,
        remark: remark.value || undefined,
      }
      await updateModelConfig(props.config.id, params)
      msg.success('已更新')
    } else {
      const params: CreateModelConfigParams = {
        configName: configName.value,
        displayName: displayName.value || undefined,
        provider: provider.value,
        modelId: modelId.value,
        baseUrl: baseUrl.value || undefined,
        api: api.value || undefined,
        apiKey: apiKey.value || undefined,
        configJson: configJson.value || undefined,
        remark: remark.value || undefined,
      }
      await createModelConfig(params)
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
          <h3>{{ editMode ? '编辑模型配置' : '创建模型配置' }}</h3>
          <button class="btn-close" @click="emit('close')">✕</button>
        </header>
        <form class="modal-body" @submit.prevent="handleSubmit">
          <label class="field">
            <span>配置名称 <em class="req">*</em> <small style="color:var(--ink-mute);font-weight:normal">（创建后不可修改）</small></span>
            <input v-model="configName" class="field-input" :disabled="editMode" placeholder="例如 deepseek" />
          </label>
          <label class="field">
            <span>显示名称</span>
            <input v-model="displayName" class="field-input" placeholder="例如「DeepSeek生产环境」" />
          </label>
          <label class="field">
            <span>Provider <em class="req">*</em></span>
            <input v-model="provider" class="field-input" placeholder="例如 deepseek" />
          </label>
          <label class="field">
            <span>Model ID <em class="req">*</em></span>
            <input v-model="modelId" class="field-input" placeholder="例如 deepseek-v4-flash" />
          </label>
          <label class="field">
            <span>Base URL</span>
            <input v-model="baseUrl" class="field-input" placeholder="例如 https://api.deepseek.com/v1" />
          </label>
          <label class="field">
            <span>API 协议</span>
            <select v-model="api" class="field-input">
              <option value="">（不指定）</option>
              <option v-for="opt in API_OPTIONS" :key="opt" :value="opt">{{ opt }}</option>
            </select>
          </label>
          <label class="field">
            <span>API Key</span>
            <div class="field-with-toggle">
              <input v-model="apiKey" class="field-input" :type="showApiKey ? 'text' : 'password'" placeholder="sk-xxx" />
              <button type="button" class="btn-toggle" @click="showApiKey = !showApiKey">
                {{ showApiKey ? '隐藏' : '查看' }}
              </button>
            </div>
          </label>
          <label class="field">
            <span>完整配置 JSON</span>
            <textarea v-model="configJson" class="field-input mono" rows="8" placeholder='{"discovery":{"type":"openai-models-list"},"modelOverrides":{...}}' style="font-size:11px;resize:vertical" />
          </label>
          <label class="field">
            <span>备注</span>
            <textarea v-model="remark" class="field-input" rows="2" placeholder="可选的备注信息" style="resize:vertical" />
          </label>
          <footer class="modal-footer">
            <button type="button" class="btn-ghost btn-reset" @click="handleReset">清空</button>
            <span style="flex:1"></span>
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
  border-radius: var(--radius); width: 520px; max-height: 90vh;
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
.field .req { color: var(--danger); font-style: normal; }
.field-input {
  padding: 8px 12px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); font-size: 13px;
  background: var(--surface); color: var(--ink);
  outline: none; transition: border-color var(--dur-fast);
  font-family: inherit;
}
.field-input:focus { border-color: var(--brand); }
select.field-input { cursor: pointer; appearance: auto; }
.field-with-toggle { display: flex; gap: 8px; align-items: stretch; }
.field-with-toggle .field-input { flex: 1; }
.btn-toggle {
  padding: 0 14px; border-radius: var(--radius-sm); border: 1px solid var(--border);
  background: var(--surface-soft); color: var(--ink-2); font-size: 12px; cursor: pointer;
  white-space: nowrap; transition: border-color var(--dur-fast), color var(--dur-fast);
}
.btn-toggle:hover { border-color: var(--brand); color: var(--brand); }
.modal-footer { display: flex; gap: 10px; justify-content: flex-end; align-items: center; }
.btn-reset { color: var(--ink-mute); }
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
