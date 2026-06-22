<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { listModelConfigs, activateModelConfig, deleteModelConfig, type ModelConfigInfo } from '@/api/system'
import ModelConfigForm from './ModelConfigForm.vue'

const msg = useMessage()
const dialog = useDialog()
const refreshStats = inject<() => Promise<void>>('refreshStats')

const configs = ref<ModelConfigInfo[]>([])
const showForm = ref(false)
const editingConfig = ref<ModelConfigInfo | null>(null)

async function load() {
  configs.value = await listModelConfigs()
}

function handleCreate() {
  editingConfig.value = null
  showForm.value = true
}
function handleEdit(c: ModelConfigInfo) {
  editingConfig.value = c
  showForm.value = true
}
async function handleActivate(c: ModelConfigInfo) {
  if (c.active) return
  try {
    await activateModelConfig(c.id)
    msg.success(`已激活「${c.configName}」`)
    await load()
    refreshStats?.()
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '激活失败')
  }
}
function handleDelete(c: ModelConfigInfo) {
  dialog.warning({
    title: '删除模型配置',
    content: `确认删除模型配置「${c.configName}」？${c.active ? '当前该配置为激活状态，删除后将使用默认模型。' : ''}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteModelConfig(c.id)
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

onMounted(load)
</script>

<template>
  <div>
    <div class="header">
      <h2 class="title">模型配置</h2>
      <button v-permission="'omp:system:model:create'" class="btn-primary" @click="handleCreate">+ 创建模型配置</button>
    </div>

    <div v-if="!configs.length" class="empty card">
      <span class="empty-icon">—</span>
      <p class="empty-text">暂无模型配置</p>
      <span class="dim" style="font-size:11px">创建模型配置后，可激活其中一个作为当前使用的模型</span>
    </div>

    <div v-else class="card">
      <div class="table-head mono">
        <span style="width:40px">#</span>
        <span style="width:140px">配置名称</span>
        <span style="width:120px">显示名称</span>
        <span style="width:100px">服务商</span>
        <span style="width:140px">模型名称</span>
        <span style="width:160px">状态</span>
        <span style="width:120px">创建时间</span>
        <span style="width:180px">操作</span>
      </div>
      <div v-for="(c, idx) in configs" :key="c.id" class="table-row" :class="{ 'row-active': c.active }">
        <span class="mono dim" data-label="#" style="width:40px">{{ String(idx + 1).padStart(2, '0') }}</span>
        <span class="mono accent" data-label="配置名称" style="width:140px" :title="c.configName">{{ c.configName }}</span>
        <span data-label="显示名称" style="width:120px" :title="c.displayName || ''">{{ c.displayName || '—' }}</span>
        <span class="mono" data-label="服务商" style="width:100px">{{ c.provider }}</span>
        <span class="mono dim" data-label="模型名称" style="width:140px;font-size:11px" :title="c.modelId">{{ c.modelId }}</span>
        <span data-label="状态" style="width:160px">
          <span class="tag" :class="c.active ? 'tag-success' : 'tag-mute'">
            <span class="status-dot" :style="{ background: c.active ? 'var(--good)' : 'var(--ink-mute)' }"></span>
            {{ c.active ? '已激活' : '未激活' }}
          </span>
        </span>
        <span class="mono dim" data-label="创建时间" style="width:120px;font-size:11px">{{ c.createdAt?.slice(0, 10) || '—' }}</span>
        <span class="row-actions" data-label="操作" style="width:180px">
          <button v-if="!c.active" v-permission="'omp:system:model:activate'" class="btn-mini" style="color:var(--good);border-color:var(--good-soft)" @click="handleActivate(c)">启用</button>
          <button v-permission="'omp:system:model:edit'" class="btn-mini" @click="handleEdit(c)">编辑</button>
          <button v-permission="'omp:system:model:delete'" class="btn-mini-danger" @click="handleDelete(c)">删除</button>
        </span>
      </div>
    </div>

    <ModelConfigForm v-if="showForm" :config="editingConfig" @close="showForm = false" @saved="onFormSaved" />
  </div>
</template>

<style scoped>
.row-active {
  background: var(--brand-soft, rgba(22,93,255,0.04));
}
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

/* row-actions 容器：桌面端横向、移动端纵向 */
.row-actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

/* 移动端：每个属性前加"标签："伪元素，让垂直堆叠时不丢语义 */
@media (max-width: 600px) {
  .table-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  /* 覆盖子组件硬编码的 width */
  .table-row > * {
    width: 100% !important;
    min-width: 0;
  }
  .row-actions {
    display: flex !important;
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }
  .row-actions > button {
    justify-content: center;
    margin-left: 0 !important;
  }
  /* 每个 span 前加 data-label 灰色标签前缀 */
  .table-row > [data-label]::before {
    content: attr(data-label) "：";
    display: inline-block;
    width: 80px;
    margin-right: 8px;
    font-size: 11px;
    color: var(--ink-faint);
    letter-spacing: 0.04em;
    text-transform: uppercase;
    flex-shrink: 0;
  }
  /* 编号本身无意义标签，"#" → 隐藏 */
  .table-row > [data-label="#"]::before { display: none; }
  /* 状态/时间这种语义弱的可以更轻量 */
  .table-row > [data-label="状态"]::before,
  .table-row > [data-label="创建时间"]::before {
    width: 80px;
  }
  /* 操作列：占满整行 */
  .table-row > [data-label="操作"] {
    margin-top: 6px;
    padding-top: 10px;
    border-top: 1px dashed var(--border);
  }
}
</style>
