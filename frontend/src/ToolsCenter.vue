<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, type AgentSkill, type AgentToolCatalog, type AgentToolView, type McpServerStatus } from './api'

const props = defineProps<{ skills: AgentSkill[]; mcpEnabled: boolean }>()
const selectedSkillId = ref('')
const catalog = ref<AgentToolCatalog | null>(null)
const mcpStatuses = ref<McpServerStatus[]>([])
const loading = ref(false)
const error = ref('')
const mcpStatusError = ref('')
let requestVersion = 0

const localTools = computed(() => catalog.value?.tools.filter(tool => tool.source === 'LOCAL') || [])
const mcpTools = computed(() => catalog.value?.tools.filter(tool => tool.source === 'MCP') || [])
const selectedSkill = computed(() => props.skills.find(skill => skill.id === selectedSkillId.value))
const enabledCount = computed(() => catalog.value?.tools.filter(tool => tool.enabledForSkill === true).length || 0)
const conditionalCount = computed(() => localTools.value.filter(tool => tool.accessMode === 'PLATFORM_CONDITIONAL').length)
const categoryDefinitions: { category: AgentToolView['category']; index: string; eyebrow: string; title: string; description: string }[] = [
  { category: 'SCOPE', index: '01', eyebrow: 'SCOPE · LOCATE', title: '范围定位', description: '先确定本轮可以读取哪些资料，再建立跨文档覆盖。' },
  { category: 'RETRIEVAL', index: '02', eyebrow: 'EVIDENCE · RETRIEVE', title: '证据检索', description: '从知识索引或原始转写中取得可引用证据。' },
  { category: 'CONTEXT', index: '03', eyebrow: 'CONTEXT · ASSIST', title: '上下文补充', description: '按需读取 Skill 资料与用户明确确认的长期记忆。' },
  { category: 'CONTROL', index: '04', eyebrow: 'CONTROL · VERIFY', title: '结果校验', description: '以结构和证据边界收束每一次 Agent 回答。' }
]
const localGroups = computed(() => categoryDefinitions.map(definition => ({
  ...definition,
  tools: localTools.value.filter(tool => tool.category === definition.category)
})).filter(group => group.tools.length))
const perspectiveSummary = computed(() => {
  if (!catalog.value) return ''
  return selectedSkill.value
    ? `${enabledCount.value} / ${catalog.value.tools.length} 获得 Skill 权限`
    : `${catalog.value.tools.length} 个进程内工具已注册`
})

async function loadTools() {
  const current = ++requestVersion
  loading.value = true
  error.value = ''
  mcpStatusError.value = ''
  const requestedSkillId = selectedSkillId.value
  if ((catalog.value?.skillId || '') !== requestedSkillId) catalog.value = null
  const query = requestedSkillId ? { params: { skillId: requestedSkillId } } : undefined
  const [catalogResult, statusResult] = await Promise.allSettled([
    api.get<AgentToolCatalog>('/agent-tools', query),
    api.get<McpServerStatus[]>('/agent-tools/mcp-status')
  ])
  if (current !== requestVersion) return
  if (catalogResult.status === 'fulfilled') catalog.value = catalogResult.value.data
  else {
    const reason: any = catalogResult.reason
    if (reason.response?.status === 404 && requestedSkillId) {
      selectedSkillId.value = ''
      return
    }
    error.value = reason.response?.data?.message || 'Tools Catalog 暂时无法读取'
  }
  if (statusResult.status === 'fulfilled') mcpStatuses.value = statusResult.value.data
  else {
    mcpStatuses.value = []
    mcpStatusError.value = 'MCP 连接状态暂时无法读取，本地工具目录不受影响。'
  }
  if (current === requestVersion) loading.value = false
}
function parameterNames(tool: AgentToolView) {
  const parameters = tool.parameters as { properties?: Record<string, unknown> } | null
  return Object.keys(parameters?.properties || {})
}
function unavailableText(tool: AgentToolView) {
  if (tool.disabledReason === 'PERSONAL_SKILL_LOCAL_ONLY') return '私人 Skill 不能调用 MCP 工具'
  return '当前 Skill 未声明此工具'
}
function accessModeLabel(tool: AgentToolView) {
  return ({
    SKILL_CONFIGURABLE: 'Skill 可配置', PLATFORM_REQUIRED: '平台强制',
    PLATFORM_CONDITIONAL: '平台按需', DEPLOYMENT_MANAGED: '部署管理'
  } as const)[tool.accessMode]
}
function permissionLabel(tool: AgentToolView) {
  if (tool.enabledForSkill === false) return 'Skill 未授予'
  if (tool.accessMode === 'PLATFORM_REQUIRED') return '平台必需'
  if (tool.accessMode === 'PLATFORM_CONDITIONAL') return '运行时条件'
  if (tool.enabledForSkill === true) return 'Skill 已授予'
  if (tool.accessMode === 'DEPLOYMENT_MANAGED') return '部署已注册'
  return '已注册'
}
function permissionClass(tool: AgentToolView) {
  if (tool.enabledForSkill === false) return 'denied'
  if (tool.accessMode === 'PLATFORM_REQUIRED') return 'required'
  if (tool.accessMode === 'PLATFORM_CONDITIONAL') return 'conditional'
  return ''
}
function mcpStatusLabel(status: McpServerStatus) {
  if (status.connected) return '已连接'
  if (status.transport === 'DISABLED') return '未启用'
  return status.failure || '未连接'
}
function mcpStatusClass(status: McpServerStatus) {
  if (status.connected) return 'connected'
  return status.transport === 'DISABLED' ? 'disabled' : 'failed'
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
    <header class="tools-intro">
      <div><p class="eyebrow">AGENT INSTRUMENTS</p><h2>Tools 中心</h2><p>理解 Agent 如何确定范围、读取证据与校验答案；需要调试时，再展开查看真实技术协议。</p></div>
      <div class="tool-tally"><b>{{ catalog?.tools.length || 0 }}</b><small>REGISTERED</small></div>
    </header>
    <section class="tool-perspective">
      <div><b>权限视角</b><span>{{ selectedSkill ? `正在检查「${selectedSkill.displayName}」声明的最小工具集合` : '当前展示进程内全部已注册工具' }}</span></div>
      <select v-model="selectedSkillId" aria-label="选择 Skill 工具视角"><option value="">全部已注册工具</option><optgroup label="内置 Skill"><option v-for="skill in skills.filter(value => value.source === 'BUILTIN')" :key="skill.id" :value="skill.id">{{ skill.displayName }}</option></optgroup><optgroup v-if="skills.some(value => value.source === 'USER')" label="我的 Skill"><option v-for="skill in skills.filter(value => value.source === 'USER')" :key="skill.id" :value="skill.id">{{ skill.displayName }}</option></optgroup></select>
      <span v-if="catalog" class="access-count">{{ perspectiveSummary }}</span>
    </section>
    <section v-if="catalog" class="catalog-summary" aria-label="工具目录摘要">
      <article><span>R</span><div><b>{{ catalog.tools.length }}</b><small>进程内已注册</small></div></article>
      <article><span>L</span><div><b>{{ localTools.length }}</b><small>本地只读工具</small></div></article>
      <article><span>◌</span><div><b>{{ conditionalCount }}</b><small>运行时有条件</small></div></article>
      <article><span>M</span><div><b>{{ mcpTools.length }}</b><small>MCP 扩展工具</small></div></article>
    </section>
    <p v-if="catalog" class="permission-legend"><span><i class="registered"></i>已注册：进程发现了工具</span><span><i class="granted"></i>Skill 已授予：该 Skill 可以申请调用</span><span><i class="conditional"></i>运行时条件：仍取决于索引、记忆或部署状态</span></p>
    <p v-if="error" class="tools-error">{{ error }} <button type="button" @click="loadTools">重试</button></p>
    <div v-if="loading && !catalog" class="tools-loading">正在读取 Agent 工具目录…</div>
    <div v-else-if="catalog" class="tool-groups" :class="{ refreshing: loading }" aria-live="polite">
      <section v-for="group in localGroups" :key="group.category" class="workflow-group">
        <header><span class="group-index">{{ group.index }}</span><div><p class="eyebrow">{{ group.eyebrow }}</p><h3>{{ group.title }}</h3><small>{{ group.description }}</small></div><em>{{ group.tools.length }}</em></header>
        <div class="tool-grid">
          <article v-for="tool in group.tools" :key="tool.name" class="tool-card" :class="{ unavailable: tool.enabledForSkill === false }">
            <header><span class="tool-index">{{ String(localTools.indexOf(tool) + 1).padStart(2, '0') }}</span><div><b>{{ tool.displayName }}</b><code>{{ tool.name }}</code></div><em :class="permissionClass(tool)">{{ permissionLabel(tool) }}</em></header>
            <p>{{ tool.uiDescription }}</p>
            <div class="availability"><span>WHEN</span><p>{{ tool.availabilityHint }}</p></div>
            <div class="tool-tags"><span>{{ accessModeLabel(tool) }}</span><span v-if="tool.dynamicParameters">Schema 随 Skill 变化</span><span v-for="name in parameterNames(tool).slice(0, 4)" :key="name">{{ name }}</span></div>
            <small v-if="tool.enabledForSkill === false" class="tool-reason">{{ unavailableText(tool) }}</small>
            <details><summary>技术协议 <i>＋</i></summary><p class="model-description">{{ tool.description }}</p><pre>{{ formattedSchema(tool) }}</pre></details>
          </article>
        </div>
      </section>
      <section class="mcp-section">
        <header><span class="group-index">05</span><div><p class="eyebrow">EXTENSION · READ ONLY</p><h3>MCP 扩展</h3><small>仅展示部署批准、通过只读白名单并成功注册的外部工具。</small></div><em>{{ mcpTools.length }}</em></header>
        <p v-if="mcpStatusError" class="mcp-status-error">{{ mcpStatusError }}</p>
        <div v-if="mcpStatuses.length" class="mcp-statuses"><span v-for="status in mcpStatuses" :key="`${status.name}-${status.transport}`" :class="mcpStatusClass(status)"><i></i>{{ status.name }} · {{ mcpStatusLabel(status) }}</span></div>
        <div v-if="mcpTools.length" class="tool-grid">
          <article v-for="tool in mcpTools" :key="tool.name" class="tool-card" :class="{ unavailable: tool.enabledForSkill === false }">
            <header><span class="tool-index mcp">M</span><div><b>{{ tool.displayName }}</b><code>{{ tool.name }}</code></div><em :class="permissionClass(tool)">{{ permissionLabel(tool) }}</em></header>
            <p>{{ tool.uiDescription }}</p>
            <div class="availability"><span>WHEN</span><p>{{ tool.availabilityHint }}</p></div>
            <div class="tool-tags"><span>{{ accessModeLabel(tool) }}</span><span v-for="name in parameterNames(tool).slice(0, 4)" :key="name">{{ name }}</span></div>
            <small v-if="tool.enabledForSkill === false" class="tool-reason">{{ unavailableText(tool) }}</small>
            <details><summary>技术协议 <i>＋</i></summary><p class="model-description">{{ tool.description }}</p><pre>{{ formattedSchema(tool) }}</pre></details>
          </article>
        </div>
        <div v-else class="mcp-empty"><span aria-hidden="true">M</span><b>{{ mcpEnabled ? '当前没有已注册的 MCP 工具' : 'MCP 扩展尚未启用' }}</b><p>{{ mcpEnabled ? '服务可能未连接，或没有工具通过部署只读白名单。' : '本地知识问答不受影响；启用后仍只允许内置 Skill 调用部署批准的只读工具。' }}</p></div>
      </section>
      <div v-if="loading" class="refresh-note">正在刷新权限视角…</div>
    </div>
  </section>
</template>

<style scoped>
.tools-page { width: min(100%, 1340px); margin: 0 auto; padding: 44px clamp(22px, 4.5vw, 72px) 76px; }
.tools-intro { display: flex; align-items: end; justify-content: space-between; gap: 28px; }
.tools-intro h2 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: clamp(38px, 4vw, 58px); }
.tools-intro p:not(.eyebrow) { max-width: 720px; margin: 12px 0 0; color: #7c838c; font-size: 14px; line-height: 1.8; }
.tool-tally { display: grid; justify-items: end; color: #59669f; }
.tool-tally b { font-family: 'DM Mono', monospace; font-size: 38px; font-weight: 500; }
.tool-tally small { color: #a2a6ac; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .12em; }
.tool-perspective { display: grid; grid-template-columns: minmax(230px, 1fr) minmax(260px, 390px) auto; align-items: center; gap: 18px; margin-top: 30px; border: 1px solid #d9dce9; border-radius: 14px; padding: 13px 15px; background: linear-gradient(110deg, #f1f3fb, #fffefa 68%); box-shadow: 0 10px 28px rgba(44, 52, 78, .035); }
.tool-perspective div { display: grid; gap: 3px; }.tool-perspective b { font-size: 12px; }.tool-perspective div span { color: #7c838c; font-size: 10px; }
.tool-perspective select { min-height: 39px; border-radius: 9px; padding: 8px 10px; font-size: 11px; }.access-count { color: #59669f; font-family: 'DM Mono', monospace; font-size: 9px; white-space: nowrap; }
.catalog-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.catalog-summary article { display: flex; min-width: 0; align-items: center; gap: 10px; border: 1px solid #e2e2dc; border-radius: 11px; padding: 10px 12px; background: #fbfaf6; }
.catalog-summary article > span { display: grid; width: 25px; height: 25px; flex: 0 0 auto; place-items: center; border-radius: 8px; color: #59669f; background: #e9ecf8; font-family: 'DM Mono', monospace; font-size: 9px; }
.catalog-summary article div { display: grid; min-width: 0; gap: 1px; }.catalog-summary b { color: #36404c; font-family: 'DM Mono', monospace; font-size: 13px; font-weight: 500; }.catalog-summary small { overflow: hidden; color: #90949a; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.permission-legend { display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 12px 2px 0; color: #8d9298; font-size: 8px; line-height: 1.6; }.permission-legend span { display: inline-flex; align-items: center; gap: 5px; }.permission-legend i { width: 6px; height: 6px; border-radius: 50%; background: #aeb2b6; }.permission-legend i.granted { background: #68a695; }.permission-legend i.conditional { background: #c69c5b; }
.tools-error, .tools-loading { margin-top: 20px; border-radius: 11px; padding: 13px 14px; color: #8e3542; background: #fff0f2; font-size: 11px; }.tools-error button { border: 0; color: inherit; background: transparent; text-decoration: underline; }.tools-loading { min-height: 260px; display: grid; place-items: center; color: #7c838c; background: #f7f6f1; }
.tool-groups { position: relative; transition: opacity .18s ease; }.tool-groups.refreshing { opacity: .62; pointer-events: none; }
.tool-groups > section { margin-top: 38px; }.tool-groups > section > header { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: end; gap: 12px; border-bottom: 1px solid #dcdcd6; padding: 0 2px 13px; }
.group-index { display: grid; width: 29px; height: 29px; place-items: center; border: 1px solid #d4d8e8; border-radius: 50%; color: #6672a6; font-family: 'DM Mono', monospace; font-size: 8px; }.tool-groups > section > header > div { min-width: 0; }.tool-groups h3 { margin-top: 3px; color: #303a46; font-family: 'Noto Serif SC', serif; font-size: 22px; }.tool-groups > section > header small { display: block; margin-top: 3px; color: #92979d; font-size: 9px; }.tool-groups > section > header > em { color: #a2a6ac; font-family: 'DM Mono', monospace; font-size: 10px; font-style: normal; }
.tool-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 13px; }
.tool-card { position: relative; min-width: 0; overflow: hidden; border: 1px solid #dfe0dc; border-radius: 14px; padding: 16px; background: linear-gradient(145deg, #fffefa, #fbfaf6); box-shadow: 0 9px 24px rgba(42, 50, 65, .026); transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
.tool-card::before { position: absolute; top: 0; left: 16px; width: 32px; height: 2px; background: #8c96c4; content: ''; }.tool-card:hover { border-color: #cfd3e3; box-shadow: 0 13px 30px rgba(42, 50, 65, .055); transform: translateY(-1px); }.tool-card.unavailable { background: #f7f6f2; opacity: .68; }
.tool-card > header { display: flex; align-items: center; gap: 10px; }.tool-index { display: grid; width: 30px; height: 30px; flex: 0 0 auto; place-items: center; border-radius: 9px; color: #fff; background: #747eb8; font-family: 'DM Mono', monospace; font-size: 8px; }.tool-index.mcp { color: #56639a; background: #e7eaf7; }.tool-card header div { display: grid; min-width: 0; gap: 2px; }.tool-card header b { color: #303a46; font-size: 13px; }.tool-card code { overflow: hidden; color: #91959b; font-family: 'DM Mono', monospace; font-size: 8px; text-overflow: ellipsis; }
.tool-card header em { margin-left: auto; border-radius: 999px; padding: 4px 7px; color: #3f735f; background: #eaf5ef; font-size: 8px; font-style: normal; white-space: nowrap; }.tool-card header em.denied { color: #8b5660; background: #f8e9ec; }.tool-card header em.required { color: #56639a; background: #e9ecf8; }.tool-card header em.conditional { color: #805b12; background: #fff3d7; }
.tool-card > p { min-height: 40px; margin: 13px 0 10px; color: #626c78; font-size: 11px; line-height: 1.75; }.availability { display: grid; grid-template-columns: 38px minmax(0, 1fr); gap: 7px; border-left: 2px solid #d8dbea; padding: 5px 0 5px 8px; }.availability > span { padding-top: 1px; color: #7b85b2; font-family: 'DM Mono', monospace; font-size: 7px; letter-spacing: .08em; }.availability p { margin: 0; color: #848a91; font-size: 9px; line-height: 1.6; }
.tool-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 11px; }.tool-tags span { border-radius: 5px; padding: 4px 6px; color: #59669f; background: #eceef8; font-family: 'DM Mono', monospace; font-size: 7px; }.tool-reason { display: block; margin-top: 9px; color: #8e4c57; font-size: 9px; }
.tool-card details { margin-top: 12px; border-top: 1px solid #e4e3dc; padding-top: 9px; }.tool-card summary { display: flex; align-items: center; justify-content: space-between; color: #777e87; cursor: pointer; font-size: 9px; list-style: none; }.tool-card summary::-webkit-details-marker { display: none; }.tool-card summary i { color: #8b94b9; font-family: 'DM Mono', monospace; font-size: 10px; font-style: normal; transition: transform .18s ease; }.tool-card details[open] summary i { transform: rotate(45deg); }.model-description { margin: 10px 0 0 !important; color: #858b92 !important; font-family: 'DM Mono', monospace; font-size: 8px !important; line-height: 1.65 !important; }.tool-card pre { max-height: 250px; overflow: auto; margin: 8px 0 0; border-radius: 8px; padding: 10px; color: #59636f; background: #f0f0ec; font-family: 'DM Mono', monospace; font-size: 8px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.mcp-section { padding-bottom: 4px; }.mcp-status-error { margin: 12px 0 0; border-radius: 8px; padding: 8px 10px; color: #805b12; background: #fff7e4; font-size: 9px; }.mcp-statuses { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }.mcp-statuses span { display: inline-flex; align-items: center; gap: 5px; border-radius: 999px; padding: 5px 8px; font-family: 'DM Mono', monospace; font-size: 8px; }.mcp-statuses i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }.mcp-statuses span.connected { color: #3f735f; background: #eaf5ef; }.mcp-statuses span.disabled { color: #777e87; background: #eeeee9; }.mcp-statuses span.failed { color: #8e4c57; background: #f8e9ec; }
.mcp-empty { display: grid; justify-items: center; margin-top: 13px; border: 1px dashed #d9dad6; border-radius: 14px; padding: 31px 20px; text-align: center; }.mcp-empty > span { display: grid; width: 34px; height: 34px; margin-bottom: 9px; place-items: center; border-radius: 11px; color: #6975a4; background: #eceef8; font-family: 'DM Mono', monospace; font-size: 10px; }.mcp-empty b { color: #4b5662; font-size: 12px; }.mcp-empty p { max-width: 540px; margin: 6px 0 0; color: #8b9096; font-size: 10px; line-height: 1.7; }
.refresh-note { position: fixed; right: 20px; bottom: 18px; z-index: 20; border-radius: 10px; padding: 9px 12px; color: #fff; background: #59669f; box-shadow: 0 10px 30px rgba(45, 54, 95, .2); font-size: 10px; }
@media (max-width: 900px) { .tool-perspective { grid-template-columns: 1fr auto; }.tool-perspective div { grid-column: 1 / -1; }.catalog-summary { grid-template-columns: 1fr 1fr; } }
@media (max-width: 720px) { .tool-grid { grid-template-columns: 1fr; }.permission-legend { display: grid; }.tool-card > p { min-height: 0; } }
@media (max-width: 540px) { .tools-page { padding: 30px 15px 55px; }.tools-intro { align-items: start; }.tools-intro h2 { font-size: 36px; }.tools-intro p:not(.eyebrow) { display: none; }.tool-tally b { font-size: 30px; }.tool-perspective { grid-template-columns: 1fr; }.tool-perspective select { width: 100%; }.access-count { justify-self: start; }.catalog-summary { gap: 6px; }.catalog-summary article { padding: 9px; }.tool-groups > section { margin-top: 31px; }.tool-groups > section > header { grid-template-columns: 30px minmax(0, 1fr) auto; gap: 8px; }.tool-groups > section > header small { display: none; }.tool-card { padding: 14px; } }
</style>
