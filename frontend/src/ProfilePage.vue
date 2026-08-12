<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type Profile, type UserMemory, type UserMemoryCandidate } from './api'

const props = defineProps<{ account: string }>()
const emit = defineEmits<{ logout: [] }>()
const loading = ref(false)
const error = ref('')
const profile = ref<Profile | null>(null)
const memoryTab = ref<'candidates' | 'memories'>('candidates')
const candidates = ref<UserMemoryCandidate[]>([])
const memories = ref<UserMemory[]>([])
const memoryLoading = ref(false)
const memoryError = ref('')
const candidateEdits = ref<Record<string, string>>({})
const memoryEdits = ref<Record<string, string>>({})

const shownAccount = computed(() => profile.value?.account || props.account || '账号')
const shortAccount = computed(() => {
  const value = shownAccount.value
  return value.length > 18 ? `${value.slice(0, 15)}…` : value
})
const initial = computed(() => shownAccount.value.trim().charAt(0).toUpperCase() || 'U')

async function loadProfile() {
  loading.value = true
  error.value = ''
  try { profile.value = (await api.get<Profile>('/profile')).data }
  catch (reason: any) { error.value = reason.response?.data?.message || '个人数据暂时无法读取' }
  finally { loading.value = false }
}
async function loadMemoryCenter() {
  memoryLoading.value = true; memoryError.value = ''
  try {
    const [candidateResponse, memoryResponse] = await Promise.all([
      api.get<UserMemoryCandidate[]>('/user-memory-candidates'), api.get<UserMemory[]>('/user-memories')
    ])
    candidates.value = candidateResponse.data; memories.value = memoryResponse.data
    candidateEdits.value = Object.fromEntries(candidates.value.map(item => [item.id, item.content]))
    memoryEdits.value = Object.fromEntries(memories.value.map(item => [item.id, item.content]))
  } catch (reason: any) { memoryError.value = reason.response?.data?.message || '记忆中心暂时无法读取' }
  finally { memoryLoading.value = false }
}
async function confirmCandidate(item: UserMemoryCandidate) {
  try { await api.post(`/user-memory-candidates/${item.id}/confirm`, { content: candidateEdits.value[item.id] }); await loadMemoryCenter() }
  catch (reason: any) { memoryError.value = reason.response?.data?.message || '无法确认这条记忆' }
}
async function rejectCandidate(item: UserMemoryCandidate) {
  try { await api.post(`/user-memory-candidates/${item.id}/reject`); await loadMemoryCenter() }
  catch (reason: any) { memoryError.value = reason.response?.data?.message || '无法拒绝这条候选' }
}
async function saveMemory(item: UserMemory) {
  try { await api.patch(`/user-memories/${item.id}`, { content: memoryEdits.value[item.id], category: item.category }); await loadMemoryCenter() }
  catch (reason: any) { memoryError.value = reason.response?.data?.message || '无法更新这条记忆' }
}
async function deleteMemory(item: UserMemory) {
  if (!window.confirm('确定删除这条长期记忆？删除后 Agent 将立即无法再使用它。')) return
  try { await api.delete(`/user-memories/${item.id}`); await loadMemoryCenter() }
  catch (reason: any) { memoryError.value = reason.response?.data?.message || '无法删除这条记忆' }
}
function logout() { emit('logout') }
function formatDate(value?: string) {
  if (!value) return '注册时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '注册时间未知' : `${new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(date)} 注册`
}
onMounted(() => { void loadProfile(); void loadMemoryCenter() })
</script>

<template>
  <section class="profile-page page-reveal">
    <header class="profile-intro"><div><p class="eyebrow">PERSONAL ARCHIVE</p><h2>个人中心</h2><p>查看账号与当前私人听记空间的简要数据。</p></div><span class="profile-avatar">{{ initial }}</span></header>
    <article class="identity-card"><div><small>ACCOUNT</small><b>{{ shownAccount }}</b><span>{{ formatDate(profile?.createdAt) }}</span></div><span class="account-mark">{{ shortAccount }}</span></article>
    <section v-if="profile" class="profile-statistics" aria-label="账号统计">
      <article><small>RECORDINGS</small><b>{{ profile.statistics.recordingCount }}</b><span>录音</span></article>
      <article><small>INDEXED</small><b>{{ profile.statistics.indexedDocumentCount }}</b><span>已收录</span></article>
      <article><small>AGENT RUNS</small><b>{{ profile.statistics.agentRunCount }}</b><span>Agent 问答</span></article>
      <article><small>MY SKILLS</small><b>{{ profile.statistics.customSkillCount }}</b><span>我的 Skill</span></article>
    </section>
    <section v-else-if="loading" class="profile-state">正在读取个人数据…</section>
    <section v-else class="profile-state error-state"><span>{{ error }}</span><button type="button" @click="loadProfile">重新读取</button></section>
    <section class="memory-center">
      <header><div><small>AGENT MEMORY</small><h3>记忆中心</h3><p>只有你确认过的候选才会成为 Agent 可检索的长期记忆。</p></div><button type="button" @click="loadMemoryCenter">刷新</button></header>
      <nav><button :class="{ active: memoryTab === 'candidates' }" @click="memoryTab = 'candidates'">待确认 · {{ candidates.length }}</button><button :class="{ active: memoryTab === 'memories' }" @click="memoryTab = 'memories'">已记住 · {{ memories.length }}</button></nav>
      <p v-if="memoryError" class="memory-error">{{ memoryError }}</p>
      <div v-if="memoryLoading" class="memory-empty">正在读取记忆…</div>
      <div v-else-if="memoryTab === 'candidates'" class="memory-list">
        <article v-for="item in candidates" :key="item.id">
          <div class="memory-meta"><span>{{ item.category }}</span><em>{{ Math.round(item.confidence * 100) }}%</em><b>{{ item.changeType === 'UPDATE' ? '更新候选' : '新候选' }}</b></div>
          <p v-if="item.currentContent" class="memory-before">当前：{{ item.currentContent }}</p>
          <textarea v-model="candidateEdits[item.id]" rows="3"></textarea><blockquote>原话：{{ item.sourceExcerpt }}</blockquote>
          <footer><button class="memory-reject" @click="rejectCandidate(item)">忽略</button><button class="memory-confirm" @click="confirmCandidate(item)">确认记住</button></footer>
        </article>
        <div v-if="!candidates.length" class="memory-empty">暂无待确认候选。Agent 不会在未经确认时保存长期记忆。</div>
      </div>
      <div v-else class="memory-list">
        <article v-for="item in memories" :key="item.id">
          <div class="memory-meta"><span>{{ item.category }}</span><em>v{{ item.versionNumber }}</em><b>{{ item.indexStatus === 'READY' ? '可检索' : '索引中' }}</b></div>
          <p v-if="item.sourceConversationDeleted" class="memory-source-deleted">原会话已删除；这条已确认记忆仍由你保留。</p>
          <textarea v-model="memoryEdits[item.id]" rows="3"></textarea>
          <footer><button class="memory-reject" @click="deleteMemory(item)">删除</button><button class="memory-confirm" @click="saveMemory(item)">保存修改</button></footer>
        </article>
        <div v-if="!memories.length" class="memory-empty">尚未确认任何长期记忆。</div>
      </div>
    </section>
    <footer class="profile-actions"><div><b>账号会话</b><span>退出后需要重新输入账号和密码。</span></div><button class="profile-logout" type="button" @click="logout">退出登录 <span>→</span></button></footer>
  </section>
</template>

<style scoped>
.profile-page { width: min(100%, 1160px); margin: 0 auto; padding: 52px clamp(22px, 5vw, 76px) 76px; }.profile-intro { display: flex; align-items: end; justify-content: space-between; gap: 28px; }.profile-intro h2 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: clamp(38px, 4vw, 58px); }.profile-intro p:not(.eyebrow) { margin: 12px 0 0; color: #7c838c; font-size: 14px; }.profile-avatar { display: grid; width: 74px; height: 74px; flex: 0 0 auto; place-items: center; border-radius: 22px; color: #fff; background: #59669f; box-shadow: 0 18px 38px rgba(58, 70, 125, .2); font-family: 'DM Mono', monospace; font-size: 26px; font-weight: 600; }
.identity-card { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-top: 40px; border-top: 1px solid #dbdcd7; border-bottom: 1px solid #dbdcd7; padding: 25px 4px; }.identity-card div { display: grid; min-width: 0; gap: 6px; }.identity-card small, .profile-statistics small { color: #92979d; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; }.identity-card b { overflow: hidden; color: #27313d; font-size: 18px; text-overflow: ellipsis; }.identity-card div span { color: #7c838c; font-size: 11px; }.account-mark { color: #b0b3ba; font-family: 'DM Mono', monospace; font-size: 11px; }
.profile-statistics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 28px; }.profile-statistics article { display: grid; min-height: 154px; align-content: space-between; border: 1px solid #e1e2dc; border-radius: 15px; padding: 18px; background: #fcfbf7; }.profile-statistics b { color: #59669f; font-family: 'DM Mono', monospace; font-size: 36px; font-weight: 500; }.profile-statistics article span { color: #59636f; font-size: 12px; }
.profile-state { display: grid; min-height: 180px; margin-top: 28px; place-items: center; border: 1px solid #e1e2dc; border-radius: 15px; color: #7c838c; background: #fcfbf7; font-size: 12px; }.error-state { align-content: center; gap: 10px; }.error-state button { border: 0; color: #59669f; background: transparent; font-size: 11px; text-decoration: underline; }
.profile-actions { display: flex; align-items: center; justify-content: space-between; gap: 22px; margin-top: 34px; border-radius: 15px; padding: 18px 20px; background: #f5f2ec; }.profile-actions div { display: grid; gap: 4px; }.profile-actions b { color: #39434f; font-size: 12px; }.profile-actions div span { color: #858a90; font-size: 10px; }.profile-logout { display: inline-flex; align-items: center; gap: 28px; border: 1px solid #e3bdc3; border-radius: 9px; padding: 9px 12px; color: #8f3543; background: #fffafa; font-size: 11px; }.profile-logout:hover { background: #fff1f2; }
.memory-center { margin-top: 34px; border-top: 1px solid #dbdcd7; padding-top: 28px; }.memory-center > header { display: flex; justify-content: space-between; gap: 20px; }.memory-center h3 { margin: 4px 0; color: #27313d; font-family: 'Noto Serif SC', serif; font-size: 27px; }.memory-center header p { color: #7c838c; font-size: 12px; }.memory-center header small { color: #92979d; font: 9px 'DM Mono', monospace; letter-spacing: .08em; }.memory-center header button { align-self: start; border: 1px solid #dddcd6; border-radius: 8px; padding: 7px 11px; background: #fff; color: #59669f; }.memory-center nav { display: flex; gap: 8px; margin: 22px 0 14px; }.memory-center nav button { border: 0; border-radius: 999px; padding: 8px 13px; color: #777f88; background: #efede7; }.memory-center nav button.active { color: #fff; background: #59669f; }.memory-list { display: grid; gap: 10px; }.memory-list article { display: grid; gap: 10px; border: 1px solid #e1e2dc; border-radius: 13px; padding: 15px; background: #fcfbf7; }.memory-list textarea { width: 100%; resize: vertical; border: 1px solid #dddcd6; border-radius: 9px; padding: 10px; color: #36404b; background: #fff; font: inherit; }.memory-meta { display: flex; align-items: center; gap: 8px; color: #747b84; font: 9px 'DM Mono', monospace; }.memory-meta span { color: #59669f; }.memory-meta b { margin-left: auto; font-weight: 500; }.memory-meta em { font-style: normal; }.memory-before, .memory-list blockquote { margin: 0; color: #858a90; font-size: 10px; }.memory-list blockquote { border-left: 2px solid #d6d7e2; padding-left: 9px; }.memory-list footer { display: flex; justify-content: flex-end; gap: 8px; }.memory-list footer button { border-radius: 8px; padding: 7px 11px; }.memory-reject { border: 1px solid #e3bdc3; color: #8f3543; background: #fffafa; }.memory-confirm { border: 0; color: #fff; background: #59669f; }.memory-empty { padding: 28px; text-align: center; color: #8a9097; font-size: 11px; }.memory-error { margin: 10px 0; color: #9d3543; font-size: 11px; }
.memory-source-deleted { margin: 0; border-radius: 7px; padding: 7px 9px; color: #7b632d; background: #fff6df; font-size: 10px; }
@media (max-width: 760px) { .profile-statistics { grid-template-columns: 1fr 1fr; }.profile-statistics article { min-height: 125px; } }
@media (max-width: 520px) { .profile-page { padding: 32px 16px 54px; }.profile-intro { align-items: start; }.profile-intro h2 { font-size: 36px; }.profile-intro p:not(.eyebrow), .account-mark { display: none; }.profile-avatar { width: 54px; height: 54px; border-radius: 16px; font-size: 19px; }.identity-card { margin-top: 28px; }.profile-statistics { gap: 8px; }.profile-statistics article { min-height: 112px; padding: 14px; }.profile-statistics b { font-size: 28px; }.profile-actions { align-items: stretch; flex-direction: column; }.profile-logout { justify-content: space-between; } }
</style>
