<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, type AgentSkill, type AgentToolCatalog, type AgentToolView } from './api'

const props = defineProps<{ skills: AgentSkill[]; mcpEnabled: boolean }>()
const selectedSkillId = ref('')
const catalog = ref<AgentToolCatalog | null>(null)
const loading = ref(false)
const error = ref('')
let requestVersion = 0

const localTools = computed(() => catalog.value?.tools.filter(tool => tool.source === 'LOCAL') || [])
const mcpTools = computed(() => catalog.value?.tools.filter(tool => tool.source === 'MCP') || [])
const enabledCount = computed(() => catalog.value?.tools.filter(tool => tool.enabledForSkill !== false).length || 0)
const selectedSkill = computed(() => props.skills.find(skill => skill.id === selectedSkillId.value))

async function loadTools() {
  const current = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const query = selectedSkillId.value ? { params: { skillId: selectedSkillId.value } } : undefined
    const value = (await api.get<AgentToolCatalog>('/agent-tools', query)).data
    if (current === requestVersion) catalog.value = value
  } catch (reason: any) {
    if (current !== requestVersion) return
    if (reason.response?.status === 404 && selectedSkillId.value) {
      selectedSkillId.value = ''
      return
    }
    error.value = reason.response?.data?.message || 'Tools Catalog 暂时无法读取'
  } finally { if (current === requestVersion) loading.value = false }
}
function parameterNames(tool: AgentToolView) {
  const parameters = tool.parameters as { properties?: Record<string, unknown> } | null
  return Object.keys(parameters?.properties || {})
}
function unavailableText(tool: AgentToolView) {
  if (tool.disabledReason === 'PERSONAL_SKILL_LOCAL_ONLY') return '私人 Skill 不能调用 MCP 工具'
  return '当前 Skill 未声明此工具'
}
function formattedSchema(tool: AgentToolView) { return JSON.stringify(tool.parameters, null, 2) }

watch(selectedSkillId, () => { void loadTools() })
watch(() => props.skills, values => {
  if (selectedSkillId.value && !values.some(skill => skill.id === selectedSkillId.value)) selectedSkillId.value = ''
})
onMounted(() => { void loadTools() })
</script>

<template>
  <section class="tools-page page-reveal">
    <header class="tools-intro"><div><p class="eyebrow">AGENT INSTRUMENTS</p><h2>Tools 中心</h2><p>查看当前 Agent 真正注册的工具，以及每个 Skill 在运行时能够获得的最小权限集合。</p></div><div class="tool-tally"><b>{{ catalog?.tools.length || 0 }}</b><small>REGISTERED</small></div></header>
    <section class="tool-perspective">
      <div><b>权限视角</b><span>{{ selectedSkill ? `正在检查「${selectedSkill.displayName}」的实际工具` : '当前展示进程内全部已注册工具' }}</span></div>
      <select v-model="selectedSkillId" aria-label="选择 Skill 工具视角"><option value="">全部已注册工具</option><optgroup label="内置 Skill"><option v-for="skill in skills.filter(value => value.source === 'BUILTIN')" :key="skill.id" :value="skill.id">{{ skill.displayName }}</option></optgroup><optgroup v-if="skills.some(value => value.source === 'USER')" label="我的 Skill"><option v-for="skill in skills.filter(value => value.source === 'USER')" :key="skill.id" :value="skill.id">{{ skill.displayName }}</option></optgroup></select>
      <span v-if="catalog" class="access-count">{{ enabledCount }} / {{ catalog.tools.length }} 可用</span>
    </section>
    <p v-if="error" class="tools-error">{{ error }} <button type="button" @click="loadTools">重试</button></p>
    <div v-if="loading && !catalog" class="tools-loading">正在读取 Agent 工具目录…</div>
    <div v-else-if="catalog" class="tool-groups" :class="{ refreshing: loading }" aria-live="polite">
      <section><header><div><p class="eyebrow">LOCAL · READ BOUNDARY</p><h3>本地工具</h3></div><span>{{ localTools.length }}</span></header><div class="tool-grid"><article v-for="tool in localTools" :key="tool.name" class="tool-card" :class="{ unavailable: tool.enabledForSkill === false }"><header><span class="tool-index">{{ String(localTools.indexOf(tool) + 1).padStart(2, '0') }}</span><div><b>{{ tool.displayName }}</b><code>{{ tool.name }}</code></div><em v-if="tool.enabledForSkill === true">已授权</em><em v-else-if="tool.enabledForSkill === false" class="denied">未授权</em><em v-else>已注册</em></header><p>{{ tool.description }}</p><div class="tool-tags"><span>{{ tool.userGrantable ? '私人 Skill 可申请' : '平台保留' }}</span><span v-if="tool.dynamicParameters">Schema 随 Skill 变化</span><span v-for="name in parameterNames(tool).slice(0, 4)" :key="name">{{ name }}</span></div><small v-if="tool.enabledForSkill === false" class="tool-reason">{{ unavailableText(tool) }}</small><details><summary>查看输入协议</summary><pre>{{ formattedSchema(tool) }}</pre></details></article></div></section>
      <section class="mcp-section"><header><div><p class="eyebrow">DEPLOYMENT APPROVED</p><h3>MCP 工具</h3></div><span>{{ mcpTools.length }}</span></header><div v-if="mcpTools.length" class="tool-grid"><article v-for="tool in mcpTools" :key="tool.name" class="tool-card" :class="{ unavailable: tool.enabledForSkill === false }"><header><span class="tool-index">M</span><div><b>{{ tool.displayName }}</b><code>{{ tool.name }}</code></div><em v-if="tool.enabledForSkill === true">已授权</em><em v-else-if="tool.enabledForSkill === false" class="denied">未授权</em><em v-else>已注册</em></header><p>{{ tool.description }}</p><small v-if="tool.enabledForSkill === false" class="tool-reason">{{ unavailableText(tool) }}</small><details><summary>查看输入协议</summary><pre>{{ formattedSchema(tool) }}</pre></details></article></div><div v-else class="mcp-empty"><b>{{ mcpEnabled ? '当前没有已注册的 MCP 工具' : 'MCP 工具尚未启用' }}</b><p>{{ mcpEnabled ? '部署批准的服务不可用或没有通过只读 allowlist。' : '本地知识问答不受影响；启用后这里只展示已连接且通过白名单的工具。' }}</p></div></section>
      <div v-if="loading" class="refresh-note">正在刷新权限视角…</div>
    </div>
  </section>
</template>

<style scoped>
.tools-page { width: min(100%, 1320px); margin: 0 auto; padding: 48px clamp(22px, 4.5vw, 72px) 78px; }.tools-intro { display: flex; align-items: end; justify-content: space-between; gap: 28px; }.tools-intro h2 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: clamp(38px, 4vw, 58px); }.tools-intro p:not(.eyebrow) { max-width: 690px; margin: 12px 0 0; color: #7c838c; font-size: 14px; line-height: 1.8; }.tool-tally { display: grid; justify-items: end; color: #59669f; }.tool-tally b { font-family: 'DM Mono', monospace; font-size: 38px; font-weight: 500; }.tool-tally small { color: #a2a6ac; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .12em; }
.tool-perspective { display: grid; grid-template-columns: minmax(230px, 1fr) minmax(260px, 390px) auto; align-items: center; gap: 18px; margin-top: 34px; border: 1px solid #dedfe8; border-radius: 14px; padding: 14px 16px; background: linear-gradient(110deg, #f4f5fb, #fffefa 65%); }.tool-perspective div { display: grid; gap: 3px; }.tool-perspective b { font-size: 12px; }.tool-perspective div span { color: #7c838c; font-size: 10px; }.tool-perspective select { min-height: 40px; border-radius: 9px; padding: 8px 10px; font-size: 11px; }.access-count { color: #59669f; font-family: 'DM Mono', monospace; font-size: 10px; white-space: nowrap; }.tools-error, .tools-loading { margin-top: 22px; border-radius: 11px; padding: 14px; color: #8e3542; background: #fff0f2; font-size: 11px; }.tools-error button { border: 0; color: inherit; background: transparent; text-decoration: underline; }.tools-loading { min-height: 260px; display: grid; place-items: center; color: #7c838c; background: #f7f6f1; }
.tool-groups { position: relative; transition: opacity .18s ease; }.tool-groups.refreshing { opacity: .62; pointer-events: none; }.tool-groups > section { margin-top: 43px; }.tool-groups > section > header { display: flex; align-items: end; justify-content: space-between; border-bottom: 1px solid #dcdcd6; padding: 0 2px 14px; }.tool-groups h3 { margin-top: 4px; color: #303a46; font-family: 'Noto Serif SC', serif; font-size: 22px; }.tool-groups > section > header > span { color: #a2a6ac; font-family: 'DM Mono', monospace; font-size: 11px; }.tool-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }.tool-card { min-width: 0; border: 1px solid #e0e1dc; border-radius: 14px; padding: 17px; background: #fcfbf7; box-shadow: 0 9px 24px rgba(42, 50, 65, .025); }.tool-card.unavailable { background: #f7f6f2; opacity: .72; }.tool-card > header { display: flex; align-items: center; gap: 11px; }.tool-index { display: grid; width: 31px; height: 31px; flex: 0 0 auto; place-items: center; border-radius: 9px; color: #fff; background: #747eb8; font-family: 'DM Mono', monospace; font-size: 9px; }.tool-card header div { display: grid; min-width: 0; gap: 3px; }.tool-card header b { color: #303a46; font-size: 13px; }.tool-card code { overflow: hidden; color: #8c9198; font-family: 'DM Mono', monospace; font-size: 9px; text-overflow: ellipsis; }.tool-card header em { margin-left: auto; border-radius: 999px; padding: 4px 7px; color: #3f735f; background: #eaf5ef; font-size: 8px; font-style: normal; white-space: nowrap; }.tool-card header em.denied { color: #8b5660; background: #f8e9ec; }.tool-card > p { min-height: 42px; margin: 15px 0 12px; color: #69727d; font-size: 11px; line-height: 1.8; }.tool-tags { display: flex; flex-wrap: wrap; gap: 5px; }.tool-tags span { border-radius: 5px; padding: 4px 6px; color: #59669f; background: #eceef8; font-family: 'DM Mono', monospace; font-size: 8px; }.tool-reason { display: block; margin-top: 10px; color: #8e4c57; font-size: 9px; }.tool-card details { margin-top: 13px; border-top: 1px solid #e4e3dc; padding-top: 10px; }.tool-card summary { color: #737b85; cursor: pointer; font-size: 9px; }.tool-card pre { max-height: 250px; overflow: auto; margin: 10px 0 0; border-radius: 8px; padding: 10px; color: #59636f; background: #f0f0ec; font-family: 'DM Mono', monospace; font-size: 8px; line-height: 1.6; white-space: pre-wrap; }.mcp-section { padding-bottom: 5px; }.mcp-empty { display: grid; justify-items: center; margin-top: 16px; border: 1px dashed #d9dad6; border-radius: 14px; padding: 38px 20px; text-align: center; }.mcp-empty b { color: #4b5662; font-size: 12px; }.mcp-empty p { max-width: 520px; margin: 7px 0 0; color: #8b9096; font-size: 10px; line-height: 1.7; }.refresh-note { position: fixed; right: 24px; bottom: 22px; z-index: 20; border-radius: 10px; padding: 9px 12px; color: #fff; background: #59669f; box-shadow: 0 10px 30px rgba(45, 54, 95, .2); font-size: 10px; }
@media (max-width: 800px) { .tool-perspective { grid-template-columns: 1fr auto; }.tool-perspective div { grid-column: 1 / -1; }.tool-grid { grid-template-columns: 1fr; } }
@media (max-width: 540px) { .tools-page { padding: 30px 15px 55px; }.tools-intro { align-items: start; }.tools-intro h2 { font-size: 36px; }.tools-intro p:not(.eyebrow) { display: none; }.tool-tally b { font-size: 30px; }.tool-perspective { grid-template-columns: 1fr; }.tool-perspective select { width: 100%; }.access-count { justify-self: end; }.tool-groups > section { margin-top: 32px; }.tool-card { padding: 14px; } }
</style>
