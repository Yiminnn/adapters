#!/usr/bin/env python3
"""Aggregate VitaBench parity runs into the `metrics` entry of parity_experiment.json.

Original side: one or more upstream simulation batch files written by run_original.sh
(`simulations[*].{task_id, trial, reward_info.reward}`); each upstream trial is one run.
Harbor side: one or more Harbor job dirs; each trial's run index is the agent kwarg
`vita_trial_index`, and its reward is `verifier/reward.txt`. Trials without a reward file
(infrastructure errors) are reported and excluded, never counted as 0.

Per run, the score is mean reward over tasks x 100 (success rate in %). Prints both sides
as mean ± sample SEM, the run-range overlap check from the adapter spec, and a ready-to-paste
metrics object.

Usage:
  python3 aggregate.py --original <batch.json> [...] --harbor <job_dir> [...] [--tasks <id> ...]
"""

from __future__ import annotations

import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path


def sem(xs: list[float]) -> float:
    n = len(xs)
    if n < 2:
        return float("nan")
    m = sum(xs) / n
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (n * (n - 1)))


def fmt(xs: list[float]) -> str:
    return f"{sum(xs) / len(xs):.2f} ± {sem(xs):.4f}" if xs else "n/a"


def original_runs(paths: list[Path], keep: set[str] | None) -> dict[int, dict[str, float]]:
    runs: dict[int, dict[str, float]] = defaultdict(dict)
    for p in paths:
        for s in json.loads(p.read_text())["simulations"]:
            tid = str(s["task_id"])
            if keep and tid not in keep:
                continue
            reward = (s.get("reward_info") or {}).get("reward")
            if reward is None:
                continue
            runs[int(s.get("trial") or 0)][tid] = float(reward)
    return runs


def harbor_runs(job_dirs: list[Path], keep: set[str] | None) -> tuple[dict[int, dict[str, float]], list[str]]:
    runs: dict[int, dict[str, float]] = defaultdict(dict)
    errored: list[str] = []
    for job in job_dirs:
        for trial in sorted(p for p in job.iterdir() if (p / "config.json").is_file()):
            cfg = json.loads((trial / "config.json").read_text())
            task_dir = Path(cfg["task"]["path"]).name  # e.g. vita-cross-10804001
            tid = re.sub(r"^vita-[a-z]+-", "", task_dir)
            if keep and tid not in keep:
                continue
            idx = int(((cfg.get("agent") or {}).get("kwargs") or {}).get("vita_trial_index") or 0)
            reward_file = trial / "verifier" / "reward.txt"
            if not reward_file.is_file():
                errored.append(f"{trial.name} (run {idx})")
                continue
            runs[idx][tid] = float(reward_file.read_text().strip())
    return runs, errored


def per_run_scores(runs: dict[int, dict[str, float]]) -> list[float]:
    return [round(100 * sum(r.values()) / len(r), 4) for _, r in sorted(runs.items()) if r]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--original", nargs="+", type=Path, required=True)
    ap.add_argument("--harbor", nargs="+", type=Path, required=True)
    ap.add_argument("--tasks", nargs="*", default=None, help="restrict to these upstream task ids")
    ap.add_argument("--benchmark-name", default="VitaBench")
    ap.add_argument("--metric", default="success_rate")
    args = ap.parse_args()
    keep = set(args.tasks) if args.tasks else None

    orig = original_runs(args.original, keep)
    harb, errored = harbor_runs(args.harbor, keep)
    o, h = per_run_scores(orig), per_run_scores(harb)

    for name, runs in (("original", orig), ("harbor", harb)):
        for idx, r in sorted(runs.items()):
            print(f"{name} run {idx}: {len(r)} tasks, success {100 * sum(r.values()) / len(r):.2f}%")
    if errored:
        print(f"harbor trials excluded (no reward, infra error): {len(errored)}: {', '.join(errored[:10])}")
    common = set().union(*orig.values()) ^ set().union(*harb.values()) if orig and harb else set()
    if common:
        print(f"WARNING: task sets differ between sides ({len(common)} ids only on one side)")

    overlap = bool(o and h and max(o) >= min(h) and max(h) >= min(o))
    print(f"original {fmt(o)}  runs={o}")
    print(f"harbor   {fmt(h)}  runs={h}")
    print(f"run ranges overlap (spec matching criterion): {overlap}")
    print(json.dumps({
        "benchmark_name": args.benchmark_name,
        "metric": args.metric,
        "original": fmt(o),
        "harbor": fmt(h),
        "original_runs": o,
        "harbor_runs": h,
    }, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
