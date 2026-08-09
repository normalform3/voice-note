<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type AgentScopeType, type SkillBlockType, type SkillDetail, type SkillResource, type SkillResourceType, type SkillSummary, type Task, type TriggerPreview } from './api'

const emit = defineEmits<{ catalogChanged: [] }>()
const catalog = ref<SkillSummary[]>([])
const selectedId = ref('')
const detail = ref<SkillDetail | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const aiGoal = ref('')
const preview = ref<TriggerPreview | null>(null)
let detailRequestVersion = 0

type DraftForm = {
  displayName: string; description: string; sceneTypes: Task['sceneType'][]; scopeTypes: AgentScopeType[]; instructions: string
  allowedTools: string[]; outputBlocks: SkillBlockType[]; shouldTrigger: string; shouldNotTrigger: string; defaultPrompt: string; resources: SkillResource[]
}
const form = ref<DraftForm>(emptyForm())
const builtIns = computed(() => catalog.value.filter(item => item.source === 'BUILTIN'))
const mine = computed(() => catalog.value.filter(item => item.source === 'USER'))
const editable = computed(() => detail.value?.source === 'USER' && detail.value.status !== 'ARCHIVED')
const shownVersion = computed(() => detail.value?.draft || detail.value?.published)
const blockOptions: { value: SkillBlockType; label: string }[] = [
  { value: 'SUMMARY', label: '总结' }, { value: 'FINDINGS', label: '发现' }, { value: 'DECISIONS', label: '决策' },
  { value: 'ACTION_ITEMS', label: '行动项' }, { value: 'OPEN_QUESTIONS', label: '未决问题' }, { value: 'QA_REVIEW', label: '问答复盘' },
  { value: 'ASSESSMENT_MATRIX', label: '评价矩阵' }, { value: 'COMPARISON_TABLE', label: '比较表' }
]
const toolOptions = [
  ['document_list', '筛选文档'], ['document_overview', '读取概览'], ['knowledge_search', '知识检索'],
  ['transcript_context', '读取原文'], ['skill_resource_read', '读取 Skill 资料'], ['finalize_answer', '提交结果']
] as const
const sceneOptions: { value: Task['sceneType']; label: string }[] = [{ value: 'MEETING', label: '会议' }, { value: 'INTERVIEW', label: '面试' }, { value: 'OTHER', label: '其他' }]
const scopeOptions: { value: AgentScopeType; label: string }[] = [{ value: 'CURRENT_DOCUMENT', label: '当前文档' }, { value: 'SELECTED_DOCUMENTS', label: '选中文档' }, { value: 'ALL_DOCUMENTS', label: '全部资料' }]
const resourceTypes: { value: SkillResourceType; label: string }[] = [{ value: 'REFERENCE', label: '参考资料' }, { value: 'TEMPLATE', label: '模板' }, { value: 'EXAMPLE', label: '示例' }]

function emptyForm(): DraftForm {
  return { displayName: '', description: '', sceneTypes: ['MEETING', 'INTERVIEW', 'OTHER'], scopeTypes: ['CURRENT_DOCUMENT', 'SELECTED_DOCUMENTS', 'ALL_DOCUMENTS'],
    instructions: '', allowedTools: ['document_list', 'document_overview', 'knowledge_search', 'transcript_context', 'skill_resource_read', 'finalize_answer'],
    outputBlocks: ['SUMMARY', 'FINDINGS'], shouldTrigger: '', shouldNotTrigger: '', defaultPrompt: '', resources: [] }
}
function lines(value: string) { return value.split('\n').map(item => item.trim()).filter(Boolean) }
function errorMessage(reason: any, fallback: string) { return reason.response?.data?.message || fallback }
function statusLabel(value: SkillSummary | SkillDetail) { return value.status === 'DRAFT' ? '草稿' : value.status === 'PUBLISHED' ? '已发布' : '已归档' }
function policyLabel(value: SkillSummary | SkillDetail) { return value.invocationPolicy === 'AUTO' ? '自动' : '手动' }
function fillForm(value: SkillDetail) {
  const version = value.draft || value.published
  form.value = { displayName: value.displayName, description: value.description, sceneTypes: [...value.sceneTypes], scopeTypes: [...value.scopeTypes],
    instructions: version?.instructions || '', allowedTools: [...(version?.allowedTools || [])], outputBlocks: [...(version?.outputBlocks || ['SUMMARY', 'FINDINGS'])],
    shouldTrigger: (version?.shouldTrigger || []).join('\n'), shouldNotTrigger: (version?.shouldNotTrigger || []).join('\n'), defaultPrompt: version?.defaultPrompt || '',
    resources: (version?.resources || []).map(item => ({ ...item, markdownContent: item.markdownContent || '' })) }
  preview.value = null
}
async function loadCatalog(preferId?: string) {
  catalog.value = (await api.get<SkillSummary[]>('/skills')).data
  const next = preferId || selectedId.value || catalog.value[0]?.id
  if (next && catalog.value.some(item => item.id === next)) await selectSkill(next)
  else { selectedId.value = ''; detail.value = null }
}
async function selectSkill(id: string) {
  const request = ++detailRequestVersion
  const previousId = detail.value?.id || ''
  selectedId.value = id; loading.value = true; error.value = ''; notice.value = ''
  try {
    const value = (await api.get<SkillDetail>(`/skills/${id}`)).data
    if (request !== detailRequestVersion) return
    detail.value = value; fillForm(value)
  } catch (reason: any) {
    if (request !== detailRequestVersion) return
    selectedId.value = previousId
    error.value = errorMessage(reason, 'Skill 详情读取失败')
  } finally { if (request === detailRequestVersion) loading.value = false }
}
async function createManual() {
  saving.value = true; error.value = ''
  try {
    const value = (await api.post<SkillDetail>('/skills', { displayName: '未命名 Skill', description: '描述这个 Skill 要解决的录音分析任务。', sceneTypes: form.value.sceneTypes, scopeTypes: form.value.scopeTypes })).data
    await loadCatalog(value.id); notice.value = '已创建私人 Draft，请按步骤完善。'; emit('catalogChanged')
  } catch (reason: any) { error.value = errorMessage(reason, '无法创建 Skill') }
  finally { saving.value = false }
}
async function createAiDraft() {
  if (!aiGoal.value.trim()) { error.value = '请先描述 Skill 目标'; return }
  saving.value = true; error.value = ''
  try {
    const value = (await api.post<SkillDetail>('/skills/ai-draft', { goal: aiGoal.value, examples: [], sceneTypes: form.value.sceneTypes, scopeTypes: form.value.scopeTypes })).data
    aiGoal.value = ''; await loadCatalog(value.id); notice.value = 'AI 已生成私人 Draft，发布前请检查指令与权限。'; emit('catalogChanged')
  } catch (reason: any) { error.value = errorMessage(reason, 'AI 草拟失败，仍可手工创建') }
  finally { saving.value = false }
}
async function saveDraft(showNotice = true) {
  if (!detail.value || !editable.value) return null
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const value = (await api.put<SkillDetail>(`/skills/${detail.value.id}/draft`, { ...form.value,
      shouldTrigger: lines(form.value.shouldTrigger), shouldNotTrigger: lines(form.value.shouldNotTrigger),
      resources: form.value.resources.map(item => ({ key: item.key, type: item.type, name: item.name, purpose: item.purpose, markdownContent: item.markdownContent || '' })) })).data
    detail.value = value; fillForm(value); await refreshCatalogOnly(); if (showNotice) notice.value = 'Draft 已保存。'; emit('catalogChanged'); return value
  } catch (reason: any) { error.value = errorMessage(reason, 'Draft 保存失败'); return null }
  finally { saving.value = false }
}
async function runPreview() {
  if (!await saveDraft(false) || !detail.value) return
  saving.value = true; error.value = ''
  try { preview.value = (await api.post<TriggerPreview>(`/skills/${detail.value.id}/trigger-preview`)).data; notice.value = preview.value.passed ? '正负触发测试全部通过。' : '触发测试存在冲突，请调整描述或样例。' }
  catch (reason: any) { error.value = errorMessage(reason, '触发测试失败') }
  finally { saving.value = false }
}
async function publishSkill() {
  if (!await saveDraft(false) || !detail.value) return false
  saving.value = true; error.value = ''
  try { detail.value = (await api.post<SkillDetail>(`/skills/${detail.value.id}/publish`)).data; fillForm(detail.value); await refreshCatalogOnly(); notice.value = '新版本已发布，当前默认仅手动调用。'; emit('catalogChanged'); return true }
  catch (reason: any) { error.value = errorMessage(reason, 'Skill 发布失败'); return false }
  finally { saving.value = false }
}
async function enableAuto() {
  if (!detail.value) return
  if (detail.value.draft && !await publishSkill()) return
  saving.value = true; error.value = ''
  try { detail.value = (await api.post<SkillDetail>(`/skills/${detail.value.id}/auto-enable`)).data; fillForm(detail.value); await refreshCatalogOnly(); notice.value = '已加入自动路由候选。'; emit('catalogChanged') }
  catch (reason: any) { error.value = errorMessage(reason, '无法开启自动路由') }
  finally { saving.value = false }
}
async function duplicateSkill() {
  if (!detail.value) return
  saving.value = true; error.value = ''
  try { const value = (await api.post<SkillDetail>(`/skills/${detail.value.id}/duplicate`)).data; await loadCatalog(value.id); notice.value = '已复制为私人 Draft。'; emit('catalogChanged') }
  catch (reason: any) { error.value = errorMessage(reason, 'Skill 复制失败') }
  finally { saving.value = false }
}
async function archiveSkill() {
  if (!detail.value || !window.confirm('归档后将从问答选择器隐藏，但历史 Run 不受影响。继续吗？')) return
  saving.value = true; error.value = ''
  try { await api.post(`/skills/${detail.value.id}/archive`); selectedId.value = ''; await loadCatalog(); notice.value = 'Skill 已归档。'; emit('catalogChanged') }
  catch (reason: any) { error.value = errorMessage(reason, 'Skill 归档失败') }
  finally { saving.value = false }
}
async function deleteSkill() {
  if (!detail.value || detail.value.source !== 'USER') return
  const name = detail.value.displayName
  if (!window.confirm(`永久删除“${name}”？\n\n该操作不可恢复，Skill 定义、全部版本和资源都会被删除；历史问答结果仍会保留。`)) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await api.delete(`/skills/${detail.value.id}`)
    detailRequestVersion++
    selectedId.value = ''; detail.value = null
    await loadCatalog()
    notice.value = `“${name}”已永久删除。`
    emit('catalogChanged')
  } catch (reason: any) { error.value = errorMessage(reason, 'Skill 删除失败') }
  finally { saving.value = false }
}
async function refreshCatalogOnly() { catalog.value = (await api.get<SkillSummary[]>('/skills')).data }
function toggle<T>(values: T[], value: T) { return values.includes(value) ? values.filter(item => item !== value) : [...values, value] }
function addResource() { if (form.value.resources.length < 10) form.value.resources.push({ key: `reference-${form.value.resources.length + 1}.md`, type: 'REFERENCE', name: '新资料', purpose: '说明何时读取这份资料', markdownContent: '' }) }
function removeResource(index: number) { form.value.resources.splice(index, 1) }
onMounted(() => { void loadCatalog().catch(reason => { error.value = errorMessage(reason, 'Skill Catalog 读取失败') }) })
</script>

<template>
  <section class="skills-page page-reveal">
    <header class="skills-intro"><div><p class="eyebrow">SKILL PLATFORM · V2</p><h2>Skill 设置</h2><p>定义 Agent 如何选择任务、读取证据并返回稳定结果。未选中的 Skill 正文不会进入问答上下文。</p></div><button class="skill-create" type="button" :disabled="saving" @click="createManual">＋ 手工创建</button></header>
    <section class="ai-draft-bar"><div><b>AI 草拟</b><span>只使用你的目标和示例，不读取私人转写；结果始终保存为 Draft。</span></div><input v-model="aiGoal" maxlength="2000" placeholder="例如：从客户访谈中提炼需求、阻塞点和原话证据"><button type="button" :disabled="saving || !aiGoal.trim()" @click="createAiDraft">生成 Draft</button></section>
    <p v-if="error" class="skill-message error">{{ error }}</p><p v-if="notice" class="skill-message success">{{ notice }}</p>

    <div class="skill-workspace">
      <aside class="skill-catalog">
        <section><header><b>内置 Skill</b><small>{{ builtIns.length }}</small></header><button v-for="skill in builtIns" :key="skill.id" type="button" :class="{ active: selectedId === skill.id }" @click="selectSkill(skill.id)"><span><b>{{ skill.displayName }}</b><small>{{ skill.description }}</small></span><em>{{ skill.version }}</em></button></section>
        <section><header><b>我的 Skill</b><small>{{ mine.length }}</small></header><button v-for="skill in mine" :key="skill.id" type="button" :class="{ active: selectedId === skill.id }" @click="selectSkill(skill.id)"><span><b>{{ skill.displayName }}</b><small>{{ skill.description }}</small></span><em>{{ policyLabel(skill) }}</em></button><p v-if="!mine.length">还没有私人 Skill，可手工创建或让 AI 草拟。</p></section>
      </aside>

      <main class="skill-editor" :class="{ loading }" :aria-busy="loading">
        <div v-if="loading && !detail" class="skill-empty">正在读取 Skill…</div>
        <div v-else-if="!detail" class="skill-empty">从左侧选择一个 Skill 开始查看。</div>
        <div v-else class="editor-body" :inert="loading ? true : undefined">
          <header class="editor-head"><div><span class="source-badge">{{ detail.source === 'BUILTIN' ? '内置' : '私人' }}</span><h3>{{ detail.displayName }}</h3><p>{{ detail.description }}</p></div><div class="editor-state"><b>{{ shownVersion?.version || '—' }}</b><small>{{ statusLabel(detail) }} · {{ policyLabel(detail) }}</small></div></header>

          <template v-if="!editable">
            <section class="read-only-card"><header><b>适用范围</b><span>{{ detail.sceneTypes.join(' · ') }} / {{ detail.scopeTypes.join(' · ') }}</span></header><p class="instructions-preview">{{ shownVersion?.instructions }}</p><div class="chip-row"><span v-for="block in shownVersion?.outputBlocks" :key="block">{{ block }}</span></div></section>
            <section v-if="shownVersion?.resources.length" class="read-only-card"><header><b>渐进式资源</b><span>运行时按需读取</span></header><article v-for="resource in shownVersion.resources" :key="resource.id"><b>{{ resource.name }}</b><p>{{ resource.purpose }}</p><small>{{ resource.type }} · {{ resource.sizeBytes }} bytes</small></article></section>
            <section class="read-only-card"><header><b>触发样例</b><span>仅用于路由元数据</span></header><div class="trigger-columns"><div><small>SHOULD TRIGGER</small><p v-for="value in shownVersion?.shouldTrigger" :key="value">＋ {{ value }}</p></div><div><small>SHOULD NOT</small><p v-for="value in shownVersion?.shouldNotTrigger" :key="value">－ {{ value }}</p></div></div></section>
            <button v-if="detail.source === 'BUILTIN'" class="secondary-action" type="button" :disabled="saving" @click="duplicateSkill">复制为我的 Skill</button>
          </template>

          <template v-else>
            <section class="editor-step"><header><span>01</span><div><b>目标与触发描述</b><small>Catalog 路由只加载这些元数据和触发样例。</small></div></header><div class="field-grid"><label>名称<input v-model="form.displayName" maxlength="120"></label><label class="wide">描述<textarea v-model="form.description" rows="3" maxlength="1000"></textarea></label><fieldset><legend>场景</legend><label v-for="option in sceneOptions" :key="option.value" class="check-pill"><input type="checkbox" :checked="form.sceneTypes.includes(option.value)" @change="form.sceneTypes = toggle(form.sceneTypes, option.value)">{{ option.label }}</label></fieldset><fieldset><legend>问答范围</legend><label v-for="option in scopeOptions" :key="option.value" class="check-pill"><input type="checkbox" :checked="form.scopeTypes.includes(option.value)" @change="form.scopeTypes = toggle(form.scopeTypes, option.value)">{{ option.label }}</label></fieldset></div></section>
            <section class="editor-step"><header><span>02</span><div><b>工作流 Instructions</b><small>明确步骤、证据要求、未知值和停止条件；最多 12,000 字。</small></div></header><textarea v-model="form.instructions" class="instruction-editor" rows="12" maxlength="12000"></textarea></section>
            <section class="editor-step"><header><span>03</span><div><b>权限与输出协议</b><small>私人 Skill 只能获得本地只读工具，并组合平台固定区块。</small></div></header><fieldset><legend>允许工具</legend><label v-for="option in toolOptions" :key="option[0]" class="check-pill"><input type="checkbox" :checked="form.allowedTools.includes(option[0])" :disabled="option[0] === 'finalize_answer'" @change="form.allowedTools = toggle(form.allowedTools, option[0])">{{ option[1] }}</label></fieldset><fieldset><legend>结果区块</legend><label v-for="option in blockOptions" :key="option.value" class="check-pill"><input type="checkbox" :checked="form.outputBlocks.includes(option.value)" @change="form.outputBlocks = toggle(form.outputBlocks, option.value)">{{ option.label }}</label></fieldset></section>
            <section class="editor-step"><header><span>04</span><div><b>参考资料、模板与示例</b><small>最多 10 份；正文仅在 Agent 主动读取时进入上下文。</small></div><button type="button" :disabled="form.resources.length >= 10" @click="addResource">＋ 添加</button></header><article v-for="(resource, index) in form.resources" :key="index" class="resource-editor"><div><select v-model="resource.type"><option v-for="option in resourceTypes" :key="option.value" :value="option.value">{{ option.label }}</option></select><input v-model="resource.name" maxlength="160" placeholder="资源名称"><button type="button" @click="removeResource(index)">移除</button></div><input v-model="resource.key" maxlength="160" placeholder="唯一 key，例如 references/checklist.md"><input v-model="resource.purpose" maxlength="500" placeholder="Agent 应该在什么情况下读取"><textarea v-model="resource.markdownContent" rows="6" placeholder="Markdown 内容，单份最多 50 KB"></textarea></article><p v-if="!form.resources.length" class="inline-empty">当前没有资源。简短 Skill 可以只使用 Instructions。</p></section>
            <section class="editor-step"><header><span>05</span><div><b>正负触发测试</b><small>每行一个问题；开启自动前至少各 3 条并全部通过。</small></div></header><div class="trigger-editors"><label>应该触发<textarea v-model="form.shouldTrigger" rows="7" placeholder="总结这次客户访谈&#10;提取客户明确提出的需求"></textarea></label><label>不应触发<textarea v-model="form.shouldNotTrigger" rows="7" placeholder="总结项目周会&#10;替我发送一封邮件"></textarea></label></div><label>问答建议<input v-model="form.defaultPrompt" maxlength="1000" placeholder="手动选择该 Skill 时显示的默认建议"></label><div v-if="preview" class="preview-result" :class="{ passed: preview.passed }"><b>{{ preview.passed ? '触发测试通过' : `发现 ${preview.conflicts.length} 个冲突` }}</b><p v-for="conflict in preview.conflicts" :key="conflict.text">{{ conflict.text }}：{{ conflict.reason }}</p></div></section>
            <section class="editor-step permission-step"><header><span>06</span><div><b>确认并发布</b><small>发布版本不可原地修改；后续编辑会创建新 Draft，历史 Run 始终冻结。</small></div></header><p>仅本账号可见 · 无脚本、Hook、网络、MCP、写操作或子 Agent 权限。</p><div class="editor-actions"><button class="secondary-action" type="button" :disabled="saving" @click="saveDraft()">保存 Draft</button><button class="secondary-action" type="button" :disabled="saving" @click="runPreview">运行触发测试</button><button class="publish-action" type="button" :disabled="saving" @click="publishSkill">发布新版本</button><button v-if="detail.published && detail.invocationPolicy !== 'AUTO'" class="auto-action" type="button" :disabled="saving" @click="enableAuto">开启自动路由</button><button class="archive-action" type="button" :disabled="saving" @click="archiveSkill">归档</button><button class="delete-action" type="button" :disabled="saving" @click="deleteSkill">永久删除</button></div></section>
          </template>
        </div>
        <div v-if="loading && detail" class="editor-loading-mask"><span>正在切换 Skill…</span></div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.skills-page { width: min(100%, 1480px); margin: 0 auto; padding: 42px clamp(20px, 4vw, 64px) 70px; }.skills-intro { display: flex; align-items: end; justify-content: space-between; gap: 24px; }.skills-intro .eyebrow { margin-bottom: 10px; }.skills-intro h2 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: clamp(36px, 4vw, 56px); }.skills-intro p:not(.eyebrow) { max-width: 680px; margin: 12px 0 0; color: #7c838c; font-size: 14px; line-height: 1.8; }.skill-create, .ai-draft-bar button, .publish-action, .auto-action { border: 0; border-radius: 10px; padding: 10px 14px; color: #fff; background: #59669f; font-size: 12px; font-weight: 600; }.skill-create:disabled, .ai-draft-bar button:disabled { opacity: .5; }
.ai-draft-bar { display: grid; grid-template-columns: minmax(210px, .7fr) minmax(280px, 1.5fr) auto; align-items: center; gap: 14px; margin-top: 28px; border: 1px solid #dfe2f0; border-radius: 14px; padding: 13px; background: linear-gradient(120deg, #f2f4fc, #fffefa); }.ai-draft-bar > div { display: grid; gap: 2px; }.ai-draft-bar b { font-size: 13px; }.ai-draft-bar span { color: #7c838c; font-size: 10px; }.ai-draft-bar input { min-height: 39px; padding: 8px 11px; }
.skill-message { margin: 13px 0 0; border-radius: 9px; padding: 9px 12px; font-size: 12px; }.skill-message.error { color: #9b2635; background: #fde8ea; }.skill-message.success { color: #246341; background: #edf8f2; }
.skill-workspace { display: grid; grid-template-columns: 285px minmax(0, 1fr); gap: 28px; margin-top: 28px; align-items: start; }.skill-catalog { position: sticky; top: 92px; display: grid; gap: 25px; }.skill-catalog section > header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; padding: 0 4px; }.skill-catalog header b { font-size: 12px; }.skill-catalog header small { color: #aeb2b6; font-family: 'DM Mono', monospace; font-size: 9px; }.skill-catalog section > button { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; border: 1px solid transparent; border-bottom-color: #e4e3dc; padding: 12px 8px; color: #586270; background: transparent; text-align: left; }.skill-catalog section > button:hover, .skill-catalog section > button.active { border-color: #d5d9eb; border-radius: 10px; background: #f5f6fc; }.skill-catalog button span { display: grid; min-width: 0; gap: 4px; }.skill-catalog button b { color: #27313d; font-size: 12px; }.skill-catalog button small { overflow: hidden; color: #7c838c; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.skill-catalog button em { color: #59669f; font-family: 'DM Mono', monospace; font-size: 9px; font-style: normal; }.skill-catalog section > p { margin: 8px 4px; color: #92979d; font-size: 11px; line-height: 1.7; }
.skill-editor { position: relative; min-width: 0; min-height: 560px; border: 1px solid #e1e1da; border-radius: 18px; padding: clamp(18px, 3vw, 34px); background: #fffefa; box-shadow: 0 14px 40px rgba(42, 50, 65, .045); }.editor-body { min-height: 490px; }.editor-loading-mask { position: absolute; z-index: 5; inset: 0; display: grid; place-items: start center; border-radius: 18px; padding-top: 120px; background: rgba(255, 254, 250, .72); backdrop-filter: blur(1.5px); }.editor-loading-mask span { border: 1px solid #daddEC; border-radius: 999px; padding: 7px 10px; color: #59669f; background: #fffefa; box-shadow: 0 8px 22px rgba(47, 57, 96, .08); font-size: 10px; }.skill-empty { display: grid; min-height: 490px; place-items: center; color: #7c838c; font-size: 13px; }.editor-head { display: flex; align-items: start; justify-content: space-between; gap: 20px; padding-bottom: 24px; border-bottom: 1px solid #e4e3dc; }.source-badge { display: inline-block; margin-bottom: 8px; border-radius: 5px; padding: 3px 6px; color: #59669f; background: #edf0fb; font-family: 'DM Mono', monospace; font-size: 8px; }.editor-head h3 { color: #27313d; font-family: 'Noto Serif SC', serif; font-size: 29px; }.editor-head p { max-width: 680px; margin: 8px 0 0; color: #7c838c; font-size: 12px; line-height: 1.7; }.editor-state { display: grid; flex: 0 0 auto; gap: 4px; text-align: right; }.editor-state b { color: #59669f; font-family: 'DM Mono', monospace; }.editor-state small { color: #92979d; font-size: 9px; }
.read-only-card, .editor-step { margin-top: 22px; border: 1px solid #e5e4de; border-radius: 13px; padding: 18px; background: #fcfbf7; }.read-only-card > header { display: flex; justify-content: space-between; gap: 12px; }.read-only-card header b { font-size: 13px; }.read-only-card header span { color: #92979d; font-size: 10px; }.instructions-preview { color: #56616e; font-size: 13px; line-height: 1.9; white-space: pre-line; }.chip-row { display: flex; flex-wrap: wrap; gap: 6px; }.chip-row span { border-radius: 6px; padding: 4px 6px; color: #59669f; background: #edf0fb; font-family: 'DM Mono', monospace; font-size: 9px; }.read-only-card article { margin-top: 12px; border-left: 2px solid #d9ddee; padding-left: 10px; }.read-only-card article b { font-size: 12px; }.read-only-card article p { margin: 3px 0; color: #7c838c; font-size: 11px; }.read-only-card article small { color: #a2a6ac; font-family: 'DM Mono', monospace; font-size: 8px; }.trigger-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 15px; }.trigger-columns small { color: #59669f; font-family: 'DM Mono', monospace; font-size: 8px; }.trigger-columns p { margin: 6px 0; color: #68717c; font-size: 11px; }
.editor-step > header { display: flex; align-items: start; gap: 11px; margin-bottom: 16px; }.editor-step > header > span { display: grid; width: 27px; height: 27px; flex: 0 0 auto; place-items: center; border-radius: 8px; color: #fff; background: #747eb8; font-family: 'DM Mono', monospace; font-size: 9px; }.editor-step > header > div { display: grid; gap: 3px; }.editor-step > header b { font-size: 13px; }.editor-step > header small { color: #7c838c; font-size: 10px; }.editor-step > header > button { margin-left: auto; border: 1px solid #cfd3e7; border-radius: 8px; padding: 6px 8px; color: #59669f; background: #fff; font-size: 10px; }.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }.field-grid .wide { grid-column: 1 / -1; }.editor-step label { gap: 5px; font-size: 11px; letter-spacing: 0; }.editor-step input, .editor-step textarea, .editor-step select { padding: 9px 10px; border-radius: 8px; font-size: 12px; }.editor-step textarea { resize: vertical; line-height: 1.7; }.instruction-editor { font-family: 'DM Mono', 'Noto Sans SC', monospace; }.editor-step fieldset { display: flex; flex-wrap: wrap; gap: 7px; margin: 10px 0 0; border: 0; padding: 0; }.editor-step legend { width: 100%; margin-bottom: 3px; color: #7c838c; font-size: 10px; }.check-pill { display: inline-flex !important; width: auto; align-items: center; gap: 5px !important; border: 1px solid #dedfe7; border-radius: 999px; padding: 5px 8px; color: #586270 !important; background: #fff; font-size: 10px !important; }.check-pill input { width: auto; padding: 0; accent-color: #59669f; }
.resource-editor { display: grid; gap: 8px; margin-top: 12px; border: 1px solid #e1e3ed; border-radius: 10px; padding: 11px; background: #fff; }.resource-editor > div { display: grid; grid-template-columns: 130px minmax(0, 1fr) auto; gap: 8px; }.resource-editor button { border: 0; color: #9b2635; background: transparent; font-size: 10px; }.inline-empty { margin: 0; color: #92979d; font-size: 11px; }.trigger-editors { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.preview-result { margin-top: 12px; border-radius: 9px; padding: 10px; color: #8a4d16; background: #fff4df; font-size: 10px; }.preview-result.passed { color: #246341; background: #edf8f2; }.preview-result p { margin: 4px 0 0; }.permission-step > p { color: #68717c; font-size: 11px; }.editor-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }.secondary-action, .publish-action, .auto-action, .archive-action, .delete-action { border-radius: 9px; padding: 8px 11px; font-size: 11px; }.secondary-action { margin-top: 18px; border: 1px solid #cfd3e7; color: #59669f; background: #fff; }.editor-actions .secondary-action { margin-top: 0; }.auto-action { background: #477b6c; }.archive-action { margin-left: auto; border: 1px solid #d7c7c2; color: #765a51; background: #fffdfa; }.delete-action { border: 1px solid #d99ea8; color: #9b2635; background: #fff4f5; }.delete-action:hover { background: #ffe9ec; }
@media (max-width: 900px) { .skill-workspace { grid-template-columns: 1fr; }.skill-catalog { position: static; grid-template-columns: 1fr 1fr; }.skill-catalog section { min-width: 0; }.ai-draft-bar { grid-template-columns: 1fr auto; }.ai-draft-bar > div { grid-column: 1 / -1; } }
@media (max-width: 590px) { .skills-page { padding: 28px 14px 50px; }.skills-intro { align-items: start; }.skills-intro h2 { font-size: 34px; }.skills-intro p { display: none; }.skill-create { padding: 8px 10px; }.ai-draft-bar { grid-template-columns: 1fr; }.skill-catalog { grid-template-columns: 1fr; }.skill-editor { min-height: 500px; padding: 15px; }.editor-head { display: grid; }.editor-state { text-align: left; }.field-grid, .trigger-editors, .trigger-columns { grid-template-columns: 1fr; }.resource-editor > div { grid-template-columns: 1fr; }.archive-action { margin-left: 0; } }
</style>
