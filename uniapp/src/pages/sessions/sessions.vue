<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { onPullDownRefresh } from '@dcloudio/uni-app'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import {
  listSessions,
  createSession,
  archive,
  unarchive,
  deleteArchivedSession,
  type SessionSummary,
} from '@/api/session'
import { listRepos, type Repo } from '@/api/repo'
import { success, warn, error, confirm } from '@/utils/toast'
import OmpIcon from '@/components/OmpIcon.vue'

const theme = useThemeStore()
const auth = useAuthStore()

// ------ 数据 ------
const sessions = ref<SessionSummary[]>([])
const repos = ref<Repo[]>([])
const loading = ref(false)

// ------ 过滤 ------
const hideArchived = ref(true)
const activeRepoTab = ref('all')

// ------ 新建会话 ------
const showCreator = ref(false)
const newSessionRepo = ref('')
const newSessionTitle = ref('')
const creating = ref(false)

// ------ 批量删除 ------
const selectedIds = ref<Set<string>>(new Set())
const MAX_BATCH_DELETE = 50

// ===== 计算属性 =====

const filteredSessions = computed(() => {
  let list = sessions.value

  // 状态过滤
  if (hideArchived.value) {
    list = list.filter((s) => s.status !== 'archived')
  }

  // 仓库过滤
  if (activeRepoTab.value !== 'all') {
    list = list.filter((s) => s.repoId === activeRepoTab.value)
  }

  // 最近活跃在前
  return [...list].sort((a, b) => {
    const da = a.lastActiveAt || a.createdAt || ''
    const db = b.lastActiveAt || b.createdAt || ''
    return db.localeCompare(da)
  })
})

const repoCounts = computed(() => {
  const map: Record<string, number> = {}
  for (const s of filteredSessions.value) {
    map[s.repoId] = (map[s.repoId] || 0) + 1
  }
  return map
})

const activeCount = computed(
  () => sessions.value.filter((s) => s.status !== 'archived').length,
)
const archivedCount = computed(
  () => sessions.value.filter((s) => s.status === 'archived').length,
)

const archivedPageIds = computed(() =>
  sessions.value.filter((s) => s.status === 'archived').map((s) => s.sessionId),
)
const someCurrentSelected = computed(() => selectedIds.value.size > 0)
const currentSelectedCount = computed(() => selectedIds.value.size)

// ===== 数据加载 =====

async function refresh() {
  loading.value = true
  try {
    const [ss, rs] = await Promise.all([listSessions(), listRepos()])
    sessions.value = ss
    repos.value = rs
    if (!newSessionRepo.value && rs.length > 0) {
      newSessionRepo.value = rs[0].repoId
    }
  } catch (e: any) {
    error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

// ===== 新建会话 =====

async function doCreate() {
  if (!newSessionRepo.value) {
    warn('请选择仓库')
    return
  }
  const title = newSessionTitle.value.trim()
  if (!title) {
    warn('请输入会话名称')
    return
  }
  creating.value = true
  try {
    const s = await createSession(
      newSessionRepo.value,
      title,
    )
    showCreator.value = false
    newSessionTitle.value = ''
    success('会话已创建')
    uni.navigateTo({ url: `/pages/chat/chat?sessionId=${s.sessionId}` })
  } catch (e: any) {
    error(e?.message || '创建失败')
  } finally {
    creating.value = false
  }
}

// ===== 会话操作 =====

async function enterChat(s: SessionSummary) {
  uni.navigateTo({ url: `/pages/chat/chat?sessionId=${s.sessionId}` })
}

async function doArchive(sessionId: string) {
  try {
    await archive(sessionId)
    success('已归档')
    await refresh()
  } catch (e: any) {
    error(e?.message || '归档失败')
  }
}

async function doUnarchive(sessionId: string) {
  try {
    await unarchive(sessionId)
    success('已恢复')
    await refresh()
  } catch (e: any) {
    error(e?.message || '恢复失败')
  }
}

async function deleteOne(sessionId: string) {
  const ok = await confirm({
    content: '确定要彻底删除此会话吗？删除后不可恢复。',
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteArchivedSession(sessionId)
    success('已删除')
    selectedIds.value.delete(sessionId)
    await refresh()
  } catch (e: any) {
    error(e?.message || '删除失败')
  }
}

async function batchDelete() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  const ok = await confirm({
    content: `确定要彻底删除已选的 ${ids.length} 个会话吗？此操作不可恢复。`,
    confirmText: '全部删除',
  })
  if (!ok) return
  try {
    const results = await Promise.allSettled(ids.map((id) => deleteArchivedSession(id)))
    const done = results.filter((r) => r.status === 'fulfilled').length
    const fail = ids.length - done
    if (fail > 0) warn(`成功 ${done}，失败 ${fail}`)
    else success(`已删除 ${done} 个会话`)
    selectedIds.value.clear()
    await refresh()
  } catch {
    error('批量删除失败')
  }
}

function toggleSelect(id: string) {
  const set = selectedIds.value
  if (set.has(id)) set.delete(id)
  else set.add(id)
  selectedIds.value = new Set(set)
}

function clearSelection() {
  selectedIds.value.clear()
}

function selectAllArchived() {
  selectedIds.value = new Set(archivedPageIds.value.slice(0, MAX_BATCH_DELETE))
}

// ===== 登出 =====
function doLogout() {
  auth.logout()
  uni.reLaunch({ url: '/pages/login/login' })
}

// ===== 仓库 tab 切换 =====
function switchRepo(repoId: string) {
  activeRepoTab.value = repoId
}
</script>

<template>
  <view :class="['page', theme.themeClass()]">
    <!-- ========== 导航栏（微信风格） ========== -->
    <view class="navbar">
      <view class="navbar-center">
        <text class="navbar-title">会话工作台</text>
      </view>
      <view class="navbar-right">
        <view class="nav-icon nav-user" @click="doLogout">
          <OmpIcon name="user" size="20" style="color: var(--brand)" />
        </view>
      </view>
    </view>

    <!-- ========== 仓库 tabs（滚动的 pill） ========== -->
    <scroll-view scroll-x class="repo-scroll" :show-scrollbar="false">
      <view class="repo-inner">
        <view
          :class="['repo-pill', { on: activeRepoTab === 'all' }]"
          @click="switchRepo('all')"
        >
          <text>全部</text>
        </view>
        <view
          v-for="repo in repos"
          :key="repo.repoId"
          :class="['repo-pill', { on: activeRepoTab === repo.repoId }]"
          @click="switchRepo(repo.repoId)"
        >
          <text>{{ repo.displayName || repo.repoId }}</text>
        </view>
      </view>
    </scroll-view>

    <!-- ========== 信息条 ========== -->
    <view class="section-strip">
      <text class="strip-label">
        {{ hideArchived ? '活跃' : '全部' }} ·
        {{ filteredSessions.length }} 个会话
      </text>
      <view class="strip-right">
        <view
          class="strip-toggle"
          @click="hideArchived = !hideArchived"
        >
          <text>{{ hideArchived ? '展开归档' : '隐藏归档' }}</text>
        </view>
      </view>
    </view>

    <!-- ========== 新建卡片按钮 ========== -->
    <view class="create-card" @click="showCreator = true">
      <OmpIcon name="plus" size="18" style="color: var(--brand)" />
      <text class="create-text">新建会话</text>
    </view>

    <!-- ========== 会话 list（微信 cell 风格） ========== -->
    <scroll-view scroll-y class="cell-list" :show-scrollbar="false">
      <!-- 加载态 -->
      <view v-if="loading" class="empty-state">
        <text class="empty-text">加载中…</text>
      </view>

      <!-- 空态 -->
      <view v-else-if="filteredSessions.length === 0" class="empty-state">
        <text class="empty-text">暂无会话</text>
        <text class="empty-hint">点击上方按钮创建新的编码会话</text>
      </view>

      <!-- 会话 cell -->
      <view v-else class="group">
        <view
          v-for="s in filteredSessions"
          :key="s.sessionId"
          :class="['cell', { 'is-archived': s.status === 'archived' }]"
        >
          <!-- 左侧选择框（仅已归档） -->
          <view
            v-if="s.status === 'archived'"
            class="cell-check"
            @click.stop="toggleSelect(s.sessionId)"
          >
            <view :class="['ck', { on: selectedIds.has(s.sessionId) }]">
              <text v-if="selectedIds.has(s.sessionId)">✓</text>
            </view>
          </view>

          <!-- avatar -->
          <view class="cell-avatar" @click="enterChat(s)">
            <text class="avatar-text">{{ (s.title || '?').charAt(0).toUpperCase() }}</text>
          </view>

          <!-- body -->
          <view class="cell-body" @click="enterChat(s)">
            <view class="cell-top">
              <text class="cell-title">{{ s.title || '未命名会话' }}</text>
              <text class="cell-time">{{ (s.lastActiveAt || s.createdAt || '').slice(5, 16) }}</text>
            </view>
            <view class="cell-bottom">
              <text class="cell-sub">{{ s.repoId }}</text>
              <view class="cell-tags">
                <view v-if="s.status === 'archived'" class="tag tag-archived">已归档</view>
              </view>
            </view>
          </view>

          <!-- 行尾操作 -->
          <view v-if="s.status !== 'archived'" class="cell-tail" @click.stop="doArchive(s.sessionId)">
            <text class="tail-action">归档</text>
          </view>
          <view v-else class="cell-tail" @click.stop="doUnarchive(s.sessionId)">
            <text class="tail-action restore">恢复</text>
          </view>
        </view>
      </view>

      <view style="height: 100px" />
    </scroll-view>

    <!-- ========== 批量删除浮动条 ========== -->
    <view v-if="someCurrentSelected" class="batch-bar">
      <text class="batch-num">已选 {{ currentSelectedCount }} 项</text>
      <view class="batch-actions">
        <text v-if="currentSelectedCount < archivedPageIds.length" class="batch-btn" @click="selectAllArchived">全选</text>
        <text class="batch-btn" @click="clearSelection">清空</text>
        <text class="batch-btn batch-danger" @click="batchDelete">批量删除</text>
      </view>
    </view>

    <!-- ========== 新建会话 bottom-sheet ========== -->
    <u-popup
      :show="showCreator"
      mode="bottom"
      round
      :custom-style="{ background: 'var(--surface)' }"
      @close="showCreator = false"
    >
      <view class="sheet">
        <text class="sheet-title">新建会话</text>

        <view class="sheet-field">
          <text class="sheet-label">选择仓库</text>
          <view v-if="repos.length === 0" class="repo-empty-text">
            <text>暂无仓库</text>
          </view>
          <scroll-view v-else scroll-y class="sheet-repo-list">
            <view
              v-for="repo in repos"
              :key="repo.repoId"
              :class="['sheet-repo', { on: newSessionRepo === repo.repoId }]"
              @click="newSessionRepo = repo.repoId"
            >
              <view :class="['rd', { on: newSessionRepo === repo.repoId }]" />
              <view class="sheet-repo-text">
                <text class="sheet-repo-name">{{ repo.displayName || repo.repoId }}</text>
                <text v-if="repo.displayName && repo.displayName !== repo.repoId" class="sheet-repo-id">{{ repo.repoId }}</text>
              </view>
            </view>
          </scroll-view>
        </view>

        <view class="sheet-field">
          <text class="sheet-label">会话名称</text>
          <input
            v-model="newSessionTitle"
            class="sheet-input"
            placeholder="给会话起个名字"
            placeholder-class="ph"
          />
        </view>

        <view class="sheet-actions">
          <view class="sheet-cancel" @click="showCreator = false">取消</view>
          <view
            class="sheet-confirm"
            :class="{ disabled: !newSessionRepo || !newSessionTitle.trim() }"
            @click="doCreate"
          >
            创建并进入
          </view>
        </view>
      </view>
    </u-popup>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background: var(--canvas);
  display: flex;
  flex-direction: column;
}

// ---- 导航栏（微信镂空风格） ----
.navbar {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 48px;
  padding: 0 16px;
  padding-top: env(safe-area-inset-top, 0);
  background: var(--canvas);
  position: relative;
  flex-shrink: 0;
}

.navbar-center {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
}

.navbar-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--ink);
  font-family: var(--font-display);
}

.navbar-right {
  position: absolute;
  right: 16px;
  display: flex;
  gap: 6px;
}

.nav-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-2);
  font-size: 17px;
  border-radius: 50%;
}

.nav-user {
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 14px;
  font-weight: 600;
}

// ---- 仓库 pill ----
.repo-scroll {
  flex-shrink: 0;
  padding: 10px 16px 0;
  white-space: nowrap;
}

.repo-inner {
  display: flex;
  gap: 8px;
}

.repo-pill {
  display: inline-flex;
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 13px;
  color: var(--ink-2);
  background: var(--surface-soft);
  border: 1px solid var(--border);
  flex-shrink: 0;
}

.repo-pill.on {
  background: var(--brand);
  color: #fff;
  border-color: var(--brand);
}

// ---- 信息条 ----
.section-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 6px;
  flex-shrink: 0;
}

.strip-label {
  font-size: 12px;
  color: var(--ink-mute);
}

.strip-right {
  display: flex;
}

.strip-toggle {
  font-size: 12px;
  color: var(--brand);
  padding: 2px 0;
}

// ---- 新建卡片 ----
.create-card {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 6px 16px 8px;
  height: 44px;
  border-radius: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
  flex-shrink: 0;
}

.create-icon {
  font-size: 20px;
  color: var(--brand);
  font-weight: 300;
  line-height: 1;
}

.create-text {
  font-size: 15px;
  color: var(--brand);
}

// ---- 会话列表 ----
.cell-list {
  flex: 1;
  padding: 0 16px 0;
}

.group {
  background: var(--surface);
  border-radius: 10px;
  overflow: hidden;
}

// 微信 cell
.cell {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  min-height: 56px;
  position: relative;
}

.cell + .cell::before {
  content: '';
  position: absolute;
  top: 0;
  left: 72px;
  right: 16px;
  height: 1px;
  background: var(--separator);
  transform: scaleY(0.5);
}

.cell.is-archived {
  opacity: 0.6;
}

// 复选框
.cell-check {
  flex-shrink: 0;
}

.ck {
  width: 20px;
  height: 20px;
  border: 1.5px solid var(--border-strong);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
}

.ck.on {
  background: var(--brand);
  border-color: var(--brand);
}

// 头像
.cell-avatar {
  width: 42px;
  height: 42px;
  border-radius: 6px;
  background: var(--brand-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-text {
  font-size: 17px;
  font-weight: 600;
  color: var(--brand);
}

// 主体
.cell-body {
  flex: 1;
  min-width: 0;
}

.cell-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.cell-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.cell-time {
  font-size: 11px;
  color: var(--ink-faint);
  flex-shrink: 0;
  margin-left: 12px;
}

.cell-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cell-sub {
  font-size: 13px;
  color: var(--ink-mute);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cell-tags {
  display: flex;
  gap: 4px;
}

.tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
}

.tag-archived {
  background: var(--surface-soft);
  color: var(--ink-faint);
}

// 行尾操作
.cell-tail {
  flex-shrink: 0;
}

.tail-action {
  font-size: 13px;
  color: var(--ink-mute);
  padding: 4px 0;
}

.tail-action.restore {
  color: var(--brand);
}

// ---- 空态 ----
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60px 20px;
}

.empty-text {
  font-size: 15px;
  color: var(--ink-mute);
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 13px;
  color: var(--ink-faint);
}

// ---- 批量删除 ----
.batch-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
  background: var(--surface);
  border-top: 1px solid var(--border);
  z-index: 500;
}

.batch-num {
  font-size: 13px;
  color: var(--ink-2);
}

.batch-actions {
  display: flex;
  gap: 16px;
}

.batch-btn {
  font-size: 13px;
  color: var(--ink-2);
}

.batch-danger {
  color: var(--danger);
}

// ---- Bottom Sheet ----
.sheet {
  padding: 24px 20px calc(24px + env(safe-area-inset-bottom));
}

.sheet-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 24px;
  text-align: center;
}

.sheet-field {
  margin-bottom: 20px;
}

.sheet-label {
  display: block;
  font-size: 14px;
  color: var(--ink-mute);
  margin-bottom: 10px;
}

.sheet-repo-list {
  max-height: 200px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}

.sheet-repo {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--separator);
}

.sheet-repo:last-child {
  border-bottom: none;
}

.sheet-repo.on {
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

.sheet-repo-text {
  flex: 1;
  min-width: 0;
}

.sheet-repo-name {
  font-size: 15px;
  color: var(--ink);
}

.sheet-repo-id {
  font-size: 11px;
  color: var(--ink-mute);
}

.repo-empty-text {
  padding: 20px;
  text-align: center;
  color: var(--ink-mute);
  font-size: 13px;
  border: 1px dashed var(--border);
  border-radius: 8px;
}

.sheet-input {
  height: 44px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-soft);
  color: var(--ink);
  font-size: 15px;
  width: 100%;
}

.ph {
  color: var(--ink-faint);
}

.sheet-actions {
  display: flex;
  gap: 12px;
  margin-top: 28px;
}

.sheet-cancel {
  flex: 1;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid var(--border);
  color: var(--ink-2);
  font-size: 16px;
}

.sheet-confirm {
  flex: 1;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  font-size: 16px;
  font-weight: 500;
}

.sheet-confirm.disabled {
  opacity: 0.4;
}
</style>