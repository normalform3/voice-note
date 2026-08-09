# Agent 评测

仓库内的 `backend/src/test/resources/agent-evaluation-set.jsonl` 定义了首批单文档、多文档、相对日期、拒答、越权和未索引当前文档场景。它只保存问题与期望，不包含真实转写或用户数据。

在目标环境执行这些场景后，将脱敏结果导出为 JSONL。每行提供：

- `relevantDocumentIds`、`retrievedDocumentIds`、`scopeDocumentIds`、`citedDocumentIds`
- `citationCount`、`validCitationCount`
- `shouldRefuse`、`didRefuse`、`budgetExhausted`

运行：

```bash
python3 scripts/evaluate_agent_results.py exported-results.jsonl
```

脚本记录 Retrieval Recall@K、文档覆盖率、引用有效率、拒答正确率和预算耗尽率。引用有效率的验收目标是 `1.0`；其余指标需先在目标模型、真实 Qdrant 索引和脱敏音频集上建立基线，本仓库不伪造离线分数。
