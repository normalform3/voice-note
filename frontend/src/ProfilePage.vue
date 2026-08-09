<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type Profile } from './api'

const props = defineProps<{ account: string }>()
const emit = defineEmits<{ logout: [] }>()
const loading = ref(false)
const error = ref('')
const profile = ref<Profile | null>(null)

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
function logout() { emit('logout') }
function formatDate(value?: string) {
  if (!value) return '注册时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '注册时间未知' : `${new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(date)} 注册`
}
onMounted(() => { void loadProfile() })
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
    <footer class="profile-actions"><div><b>账号会话</b><span>退出后需要重新输入账号和密码。</span></div><button class="profile-logout" type="button" @click="logout">退出登录 <span>→</span></button></footer>
  </section>
</template>

<style scoped>
.profile-page { width: min(100%, 1160px); margin: 0 auto; padding: 52px clamp(22px, 5vw, 76px) 76px; }.profile-intro { display: flex; align-items: end; justify-content: space-between; gap: 28px; }.profile-intro h2 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: clamp(38px, 4vw, 58px); }.profile-intro p:not(.eyebrow) { margin: 12px 0 0; color: #7c838c; font-size: 14px; }.profile-avatar { display: grid; width: 74px; height: 74px; flex: 0 0 auto; place-items: center; border-radius: 22px; color: #fff; background: #59669f; box-shadow: 0 18px 38px rgba(58, 70, 125, .2); font-family: 'DM Mono', monospace; font-size: 26px; font-weight: 600; }
.identity-card { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-top: 40px; border-top: 1px solid #dbdcd7; border-bottom: 1px solid #dbdcd7; padding: 25px 4px; }.identity-card div { display: grid; min-width: 0; gap: 6px; }.identity-card small, .profile-statistics small { color: #92979d; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; }.identity-card b { overflow: hidden; color: #27313d; font-size: 18px; text-overflow: ellipsis; }.identity-card div span { color: #7c838c; font-size: 11px; }.account-mark { color: #b0b3ba; font-family: 'DM Mono', monospace; font-size: 11px; }
.profile-statistics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 28px; }.profile-statistics article { display: grid; min-height: 154px; align-content: space-between; border: 1px solid #e1e2dc; border-radius: 15px; padding: 18px; background: #fcfbf7; }.profile-statistics b { color: #59669f; font-family: 'DM Mono', monospace; font-size: 36px; font-weight: 500; }.profile-statistics article span { color: #59636f; font-size: 12px; }
.profile-state { display: grid; min-height: 180px; margin-top: 28px; place-items: center; border: 1px solid #e1e2dc; border-radius: 15px; color: #7c838c; background: #fcfbf7; font-size: 12px; }.error-state { align-content: center; gap: 10px; }.error-state button { border: 0; color: #59669f; background: transparent; font-size: 11px; text-decoration: underline; }
.profile-actions { display: flex; align-items: center; justify-content: space-between; gap: 22px; margin-top: 34px; border-radius: 15px; padding: 18px 20px; background: #f5f2ec; }.profile-actions div { display: grid; gap: 4px; }.profile-actions b { color: #39434f; font-size: 12px; }.profile-actions div span { color: #858a90; font-size: 10px; }.profile-logout { display: inline-flex; align-items: center; gap: 28px; border: 1px solid #e3bdc3; border-radius: 9px; padding: 9px 12px; color: #8f3543; background: #fffafa; font-size: 11px; }.profile-logout:hover { background: #fff1f2; }
@media (max-width: 760px) { .profile-statistics { grid-template-columns: 1fr 1fr; }.profile-statistics article { min-height: 125px; } }
@media (max-width: 520px) { .profile-page { padding: 32px 16px 54px; }.profile-intro { align-items: start; }.profile-intro h2 { font-size: 36px; }.profile-intro p:not(.eyebrow), .account-mark { display: none; }.profile-avatar { width: 54px; height: 54px; border-radius: 16px; font-size: 19px; }.identity-card { margin-top: 28px; }.profile-statistics { gap: 8px; }.profile-statistics article { min-height: 112px; padding: 14px; }.profile-statistics b { font-size: 28px; }.profile-actions { align-items: stretch; flex-direction: column; }.profile-logout { justify-content: space-between; } }
</style>
