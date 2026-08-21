# 回答结构

- `SUMMARY`：先给出直接答案，并以 `statements` 拆分事实句。
- `FINDINGS`：列出支撑答案的事实，每项使用句级 `statements`。
- `COMPARISON_TABLE`：仅在用户要求比较或存在多个对象时使用相同列，每个 `cell` 单独绑定证据。

每个 statement 或 cell 自己携带 `evidenceRefs`，不要把整个条目的来源合并到一起。
