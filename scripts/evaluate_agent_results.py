#!/usr/bin/env python3
"""Compute repeatable Agent evaluation metrics from exported JSONL results.

Each line contains: relevantDocumentIds, retrievedDocumentIds, scopeDocumentIds,
citedDocumentIds, citationCount, validCitationCount, shouldRefuse, didRefuse,
and budgetExhausted. Raw transcript text is neither required nor read.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 1.0


def main(path: str) -> None:
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    relevant = sum(len(set(row.get("relevantDocumentIds", []))) for row in rows)
    recalled = sum(len(set(row.get("relevantDocumentIds", [])) & set(row.get("retrievedDocumentIds", []))) for row in rows)
    scoped = sum(len(set(row.get("scopeDocumentIds", []))) for row in rows)
    covered = sum(len(set(row.get("scopeDocumentIds", [])) & set(row.get("retrievedDocumentIds", []))) for row in rows)
    citations = sum(int(row.get("citationCount", 0)) for row in rows)
    valid_citations = sum(int(row.get("validCitationCount", 0)) for row in rows)
    refusal_cases = [row for row in rows if row.get("shouldRefuse")]
    correct_refusals = sum(bool(row.get("didRefuse")) for row in refusal_cases)
    exhausted = sum(bool(row.get("budgetExhausted")) for row in rows)
    print(json.dumps({
        "cases": len(rows),
        "retrievalRecallAtK": ratio(recalled, relevant),
        "documentCoverage": ratio(covered, scoped),
        "citationValidity": ratio(valid_citations, citations),
        "correctRefusalRate": ratio(correct_refusals, len(refusal_cases)),
        "budgetExhaustionRate": ratio(exhausted, len(rows)),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: evaluate_agent_results.py <exported-results.jsonl>")
    main(sys.argv[1])
