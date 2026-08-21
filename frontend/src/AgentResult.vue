<script setup lang="ts">
import AgentEvidenceDisclosure from './AgentEvidenceDisclosure.vue'
import { type AgentEvidence, type AgentResult, type ResultCitation, type ResultItem } from './api'

const props = defineProps<{ result: AgentResult; evidence: AgentEvidence[] }>()
const emit = defineEmits<{ evidence: [citation: ResultCitation] }>()

const blockLabels: Record<string, string> = {
  SUMMARY: '总结', FINDINGS: '关键发现', DECISIONS: '决策', ACTION_ITEMS: '行动项', OPEN_QUESTIONS: '未决问题',
  QA_REVIEW: '问答复盘', ASSESSMENT_MATRIX: '评价矩阵', COMPARISON_TABLE: '比较表'
}
function itemTitle(item: ResultItem, index: number) { return item.title || item.question || item.dimension || item.label || `条目 ${index + 1}` }
function itemBody(item: ResultItem) { return item.content || item.answer || item.assessment || '' }
function itemMeta(item: ResultItem) {
  return [item.status, item.owner ? `负责人 ${item.owner}` : '', item.dueAt ? `期限 ${item.dueAt}` : '', item.followUp ? `追问 ${item.followUp}` : ''].filter(Boolean).join(' · ')
}
</script>

<template>
  <template v-if="result.resultSchemaVersion && result.resultSchemaVersion >= 2 && result.blocks">
    <article v-for="(block, blockIndex) in result.blocks" :key="`${block.type}-${blockIndex}`" class="agent-result-block" :class="`block-${block.type.toLowerCase()}`">
      <header><b>{{ block.title || blockLabels[block.type] || block.type }}</b><small>{{ block.type }}</small></header>
      <AgentEvidenceDisclosure v-if="block.statements?.length || block.content || block.evidence?.length" class="block-copy"
        :statements="block.statements" :text="block.content" :citations="block.evidence" :evidence="evidence" @evidence="emit('evidence', $event)" />

      <div v-if="block.items?.length" class="block-items">
        <section v-for="(item, index) in block.items" :key="index" class="block-item">
          <div><b>{{ itemTitle(item, index) }}</b><em v-if="item.status || item.assessment">{{ item.status || item.assessment }}</em></div>
          <AgentEvidenceDisclosure v-if="item.statements?.length || itemBody(item) || item.evidence?.length" class="item-copy"
            :statements="item.statements" :text="itemBody(item)" :citations="item.evidence" :evidence="evidence" @evidence="emit('evidence', $event)">
            <template #after-copy><small v-if="itemMeta(item)" class="item-meta">{{ itemMeta(item) }}</small></template>
          </AgentEvidenceDisclosure>
          <small v-else-if="itemMeta(item)" class="item-meta standalone">{{ itemMeta(item) }}</small>
        </section>
      </div>

      <div v-if="block.columns?.length && block.rows" class="comparison-scroll">
        <table><thead><tr><th v-for="column in block.columns" :key="column">{{ column }}</th><th v-if="result.resultSchemaVersion === 2">依据</th></tr></thead>
          <tbody><tr v-for="(row, index) in block.rows" :key="index"><td>{{ row.label || row.title || `对象 ${index + 1}` }}</td>
            <template v-if="result.resultSchemaVersion === 3"><td v-for="(cell, cellIndex) in row.cells || []" :key="cellIndex"><AgentEvidenceDisclosure class="table-cell-copy" :statements="[cell]" :evidence="evidence" compact @evidence="emit('evidence', $event)" /></td></template>
            <template v-else><td v-for="(value, valueIndex) in row.values || []" :key="valueIndex">{{ value }}</td><td><AgentEvidenceDisclosure :citations="row.evidence" :evidence="evidence" toggle-label="查看整行依据" compact @evidence="emit('evidence', $event)" /></td></template>
          </tr></tbody>
        </table>
      </div>
    </article>
  </template>
  <template v-else>
    <p class="answer">{{ result.answer }}</p>
    <article v-for="(finding, index) in result.findings || []" :key="index" class="finding"><b>{{ finding.title || `发现 ${index + 1}` }}</b><AgentEvidenceDisclosure class="finding-copy" :text="finding.content" :citations="finding.evidence" :evidence="evidence" @evidence="emit('evidence', $event)" /></article>
  </template>
</template>

<style scoped>
.agent-result-block { padding: 15px 0 2px; border-top: 1px solid #e4e3dc; }.agent-result-block:first-child { margin-top: 14px; }.agent-result-block > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.agent-result-block > header b { color: #27313d; font-size: 13px; }.agent-result-block > header small { color: #aeb2b6; font-family: 'DM Mono', monospace; font-size: 8px; }
.block-copy { margin-top: 8px; }.block-copy :deep(.evidence-copy) { color: #3a4554; font-family: 'Noto Serif SC', serif; font-size: 14px; font-weight: 600; line-height: 1.8; }
.block-items { display: grid; gap: 10px; margin-top: 10px; }.block-item { border-left: 2px solid #d7dbee; padding: 3px 0 3px 10px; }.block-item > div { display: flex; align-items: start; justify-content: space-between; gap: 8px; }.block-item b { color: #3c4651; font-size: 12px; }.block-item em { flex: 0 0 auto; border-radius: 5px; padding: 2px 5px; color: #59669f; background: #edf0fb; font-family: 'DM Mono', monospace; font-size: 8px; font-style: normal; }.item-copy { margin-top: 5px; }.item-copy :deep(.evidence-copy) { color: #68717c; font-size: 12px; line-height: 1.7; }.item-meta { display: block; margin-top: 5px; color: #92979d; font-size: 9px; }.item-meta.standalone { margin-left: 0; }
.comparison-scroll { margin-top: 10px; overflow-x: auto; }.comparison-scroll table { width: 100%; border-collapse: collapse; color: #586270; font-size: 10px; }.comparison-scroll th, .comparison-scroll td { min-width: 78px; border: 1px solid #e4e3dc; padding: 7px; text-align: left; vertical-align: top; }.comparison-scroll th { color: #59669f; background: #f4f5fb; font-weight: 600; }
.table-cell-copy :deep(.evidence-copy) { color: inherit; font-size: inherit; line-height: 1.55; }
.answer { margin: 15px 0 0; color: #3a4554; font-family: 'Noto Serif SC', serif; font-size: 15px; font-weight: 600; line-height: 1.85; }.finding { padding: 14px 0 2px; border-top: 1px solid #e4e3dc; }.finding:first-of-type { margin-top: 15px; }.finding b { color: #27313d; font-size: 14px; }.finding-copy { margin-top: 7px; }.finding-copy :deep(.evidence-copy) { color: #65707a; font-size: 15px; line-height: 1.85; }
</style>
