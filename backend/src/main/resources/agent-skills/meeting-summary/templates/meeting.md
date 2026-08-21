# 字段约定

- 决策：`title`、`statements`、`status`
- 行动项：`title`、`statements`、`owner`、`dueAt`、`status`
- 未决问题：`title`、`statements`、`status`

每个 statement 自己携带 `evidenceRefs`，不要把整个条目的来源合并到一起。
