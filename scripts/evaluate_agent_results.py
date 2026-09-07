#!/usr/bin/env python3
"""Compute document, segment, citation, quality, and latency metrics from exported JSONL.

Chunk-aware rows may add: relevantSegmentIds, retrievedChunks (ordered objects with
segmentIds and oversized), queryType, sceneProfile, and latencyMs. Raw transcript
text is neither required nor read.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any


def ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 1.0


def optional_ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def retrieved_chunks(row: dict[str, Any]) -> list[dict[str, Any]]:
    chunks = row.get("retrievedChunks")
    if isinstance(chunks, list):
        return [chunk for chunk in chunks if isinstance(chunk, dict)]
    segment_ids = row.get("retrievedSegmentIds", [])
    return [{"segmentIds": [segment_id]} for segment_id in segment_ids]


def percentile95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * 0.95) - 1)], 2)


def retrieval_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    relevant_documents = sum(len(set(row.get("relevantDocumentIds", []))) for row in rows)
    recalled_documents = sum(len(set(row.get("relevantDocumentIds", [])) & set(row.get("retrievedDocumentIds", []))) for row in rows)
    scoped_documents = sum(len(set(row.get("scopeDocumentIds", []))) for row in rows)
    covered_documents = sum(len(set(row.get("scopeDocumentIds", [])) & set(row.get("retrievedDocumentIds", []))) for row in rows)

    relevant_segments = 0
    recalled_segments = 0
    retrieved_unique_segments = 0
    relevant_context_segments = 0
    duplicate_segments = 0
    retrieved_segment_occurrences = 0
    reciprocal_ranks: list[float] = []
    ndcgs: list[float] = []
    oversized_chunks = 0
    total_chunks = 0
    latencies: list[float] = []

    for row in rows:
        relevant = set(row.get("relevantSegmentIds", []))
        chunks = retrieved_chunks(row)
        ranked_segments = [segment_id for chunk in chunks for segment_id in chunk.get("segmentIds", [])]
        unique_segments = set(ranked_segments)
        if relevant:
            top_eight = {segment_id for chunk in chunks[:8] for segment_id in chunk.get("segmentIds", [])}
            relevant_segments += len(relevant)
            recalled_segments += len(relevant & top_eight)
            retrieved_unique_segments += len(unique_segments)
            relevant_context_segments += len(relevant & unique_segments)
            gains = [1 if relevant.intersection(chunk.get("segmentIds", [])) else 0 for chunk in chunks[:8]]
            first = next((index for index, gain in enumerate(gains, 1) if gain), None)
            reciprocal_ranks.append(0.0 if first is None else 1.0 / first)
            dcg = sum(gain / math.log2(index + 1) for index, gain in enumerate(gains, 1))
            ideal_count = min(8, len(relevant))
            ideal = sum(1 / math.log2(index + 1) for index in range(1, ideal_count + 1))
            ndcgs.append(0.0 if ideal == 0 else min(1.0, dcg / ideal))
        duplicate_segments += len(ranked_segments) - len(unique_segments)
        retrieved_segment_occurrences += len(ranked_segments)
        oversized_chunks += sum(bool(chunk.get("oversized")) for chunk in chunks)
        total_chunks += len(chunks)
        if isinstance(row.get("latencyMs"), (int, float)): latencies.append(float(row["latencyMs"]))

    citations = sum(int(row.get("citationCount", 0)) for row in rows)
    valid_citations = sum(int(row.get("validCitationCount", 0)) for row in rows)
    refusal_cases = [row for row in rows if row.get("shouldRefuse")]
    correct_refusals = sum(bool(row.get("didRefuse")) for row in refusal_cases)
    exhausted = sum(bool(row.get("budgetExhausted")) for row in rows)
    return {
        "cases": len(rows),
        "retrievalRecallAtK": ratio(recalled_documents, relevant_documents),
        "documentCoverage": ratio(covered_documents, scoped_documents),
        "segmentRecallAt8": optional_ratio(recalled_segments, relevant_segments),
        "mrrAt8": round(sum(reciprocal_ranks) / len(reciprocal_ranks), 4) if reciprocal_ranks else None,
        "ndcgAt8": round(sum(ndcgs) / len(ndcgs), 4) if ndcgs else None,
        "contextPrecision": optional_ratio(relevant_context_segments, retrieved_unique_segments),
        "duplicateSegmentRate": optional_ratio(duplicate_segments, retrieved_segment_occurrences),
        "citationValidity": ratio(valid_citations, citations),
        "oversizedChunkRate": optional_ratio(oversized_chunks, total_chunks),
        "p95LatencyMs": percentile95(latencies),
        "correctRefusalRate": ratio(correct_refusals, len(refusal_cases)),
        "budgetExhaustionRate": ratio(exhausted, len(rows)),
    }


def grouped(rows: list[dict[str, Any]], field: str) -> dict[str, Any]:
    values: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        value = row.get(field)
        if value: values.setdefault(str(value), []).append(row)
    return {value: retrieval_metrics(group) for value, group in sorted(values.items())}


def main(path: str) -> None:
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    output = retrieval_metrics(rows)
    output["bySceneProfile"] = grouped(rows, "sceneProfile")
    output["byQueryType"] = grouped(rows, "queryType")
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: evaluate_agent_results.py <exported-results.jsonl>")
    main(sys.argv[1])
