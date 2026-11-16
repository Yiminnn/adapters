"""Generate Harbor tasks from SecCodeBench (alibaba/sec-code-bench).

One Harbor task per (case, scenario). The agent sees the upstream E2E prompt
(sec_code_bench/e2e.py: last output-format section replaced by EXTEND_PROMPT)
in an empty /app; the verifier reruns the upstream functional + security pytest
suites in-container (see task-template/tests/verify.py).

The upstream checkout is only read, never executed.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

SOURCE_REPO = "https://github.com/alibaba/sec-code-bench.git"
SOURCE_COMMIT = "bf0c4d727098733af34c7add14a8d37ee84fd2a7"
ORG = "alibaba"
SCENARIOS = ("gen", "gen-hints", "fix", "fix-hints")
PROMPT_SUFFIX = {"gen": "", "gen-hints": "Hints", "fix": "Fix", "fix-hints": "FixHints"}
# Languages whose verifier has been ported into the task container so far.
SUPPORTED_LANGUAGES = ("python",)
LANGUAGE_JSON = {"python": "python/python.json"}

# Verbatim from sec_code_bench/e2e.py
OUTPUT_FORMAT_SECTION_HEADERS = ("## Output Format", "## 输出格式", "## Output", "## output")
EXTEND_PROMPT = """
## Output Format

The file name and corresponding path are:
{file_path}

Please provide the complete code, including necessary imports and full definitions, and **write the code to the corresponding file**. Pay attention to newline encoding and escaping.
"""

TEMPLATE_DIR = Path(__file__).parent / "task-template"
ORACLE_DIR = Path(__file__).parent / "oracles"


def e2e_prompt(prompt: str, params: dict[str, str]) -> str:
    pattern = "|".join(re.escape(h) for h in OUTPUT_FORMAT_SECTION_HEADERS)
    matches = list(re.finditer(pattern, prompt))
    if matches:
        prompt = prompt[: matches[-1].start()].rstrip()
    return prompt + "\n\n" + EXTEND_PROMPT.format(file_path=params)


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


@dataclass
class Case:
    language: str
    case_id: str
    spec: dict

    @property
    def target_file(self) -> str:
        (path,) = self.spec["params"].values()  # every python case writes exactly one file
        return path

    @property
    def verifier_repo(self) -> str:
        # http://python-verifier:5000/verify/<category>/<repo>  ->  python_bench/<category>/<repo>
        url = self.spec["verify_urls"]["gen"]
        return "python_bench/" + url.split("/verify/", 1)[1]


class SeccodebenchAdapter:
    def __init__(
        self,
        output_dir: Path,
        source_dir: Path | None = None,
        limit: int | None = None,
        overwrite: bool = False,
        task_ids: list[str] | None = None,
        languages: list[str] | None = None,
        scenarios: list[str] | None = None,
        solution_mode: str = "oracle",
    ) -> None:
        self.output_dir = Path(output_dir)
        self.source_dir = Path(source_dir) if source_dir else None
        self.limit = limit
        self.overwrite = overwrite
        self.task_ids = {t.lower() for t in task_ids} if task_ids else None
        self.languages = languages or list(SUPPORTED_LANGUAGES)
        self.scenarios = scenarios or list(SCENARIOS)
        self.solution_mode = solution_mode
        for lang in self.languages:
            if lang not in SUPPORTED_LANGUAGES:
                raise ValueError(f"language {lang!r} not supported yet (have {SUPPORTED_LANGUAGES})")

    # ------------------------------------------------------------------ source
    def ensure_source(self) -> Path:
        if self.source_dir is None:
            self.source_dir = Path.home() / ".cache" / "harbor-seccodebench" / SOURCE_COMMIT[:12]
        if not (self.source_dir / "datasets").is_dir():
            self.source_dir.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "clone", "-q", SOURCE_REPO, str(self.source_dir)], check=True)
        subprocess.run(
            ["git", "-C", str(self.source_dir), "checkout", "-q", SOURCE_COMMIT], check=True
        )
        return self.source_dir

    def load_cases(self) -> list[Case]:
        src = self.ensure_source()
        cases = []
        for lang in self.languages:
            data = json.loads((src / "datasets/benchmark" / LANGUAGE_JSON[lang]).read_text())
            cases += [Case(lang, cid, spec) for cid, spec in sorted(data.items())]
        return cases

    @staticmethod
    def task_id(case: Case, scenario: str) -> str:
        return f"{case.language}-{slug(case.case_id)}-{scenario}"

    # ---------------------------------------------------------------- generate
    def run(self) -> list[Path]:
        written = []
        for case in self.load_cases():
            for scenario in self.scenarios:
                if scenario not in case.spec["scenarios"]:
                    continue
                tid = self.task_id(case, scenario)
                if self.task_ids and not ({tid, case.case_id.lower()} & self.task_ids):
                    continue
                if self.limit is not None and len(written) >= self.limit:
                    return written
                out = self.output_dir / tid
                if out.exists():
                    if not self.overwrite:
                        continue
                    shutil.rmtree(out)
                self.write_task(case, scenario, tid, out)
                written.append(out)
        return written

    def write_task(self, case: Case, scenario: str, tid: str, out: Path) -> None:
        src = self.source_dir
        lang_dir = src / "datasets/benchmark" / case.language
        prompt_file = (
            lang_dir
            / "prompts"
            / f"{case.spec['prompt']}{PROMPT_SUFFIX[scenario]}.{case.spec['locale']}"
        )
        repo = src / "docker/model" / case.language / case.verifier_repo
        target = case.target_file

        shutil.copytree(TEMPLATE_DIR, out)
        subs = {
            "{task_name}": f"{ORG}/seccodebench__{tid}",
            "{language}": case.language,
            "{case_id}": case.case_id,
            "{scenario}": scenario,
            "{severity}": case.spec.get("severity", "unknown"),
            "{source_commit}": SOURCE_COMMIT,
            "{target_file}": target,
            "{oracle_filename}": Path(target).name,
        }
        for rel in ("task.toml", "tests/test.sh", "solution/solve.sh"):
            p = out / rel
            text = p.read_text()
            for k, v in subs.items():
                text = text.replace(k, v)
            p.write_text(text)

        (out / "instruction.md").write_text(
            e2e_prompt(prompt_file.read_text(encoding="utf-8"), case.spec["params"]),
            encoding="utf-8",
        )

        # environment: case requirements (torch pinned to the CPU wheel, as upstream does on Linux)
        req = (repo / "requirements.txt").read_text()
        req = re.sub(r"torch==(\d+\.\d+\.\d+)(?!\+)", r"torch==\1+cpu", req)
        (out / "environment/requirements.txt").write_text(req)

        # tests: verifier repo minus the file the agent must write
        shutil.copytree(
            repo, out / "tests/repo", ignore=shutil.ignore_patterns("__pycache__", "*.pyc")
        )
        (out / "tests/repo" / target).unlink(missing_ok=True)

        self.write_solution(case, repo, out)
        for sh in out.rglob("*.sh"):
            sh.chmod(0o755)

    def write_solution(self, case: Case, repo: Path, out: Path) -> None:
        target = case.target_file
        dst = out / "solution" / Path(target).name
        if self.solution_mode == "vulnerable":
            # negative control: the upstream template's (vulnerable) implementation
            shutil.copy(repo / target, dst)
            return
        oracle = ORACLE_DIR / case.language / f"{case.case_id}.py"
        if oracle.exists():
            shutil.copy(oracle, dst)
        else:
            (out / "solution/solve.sh").write_text(
                f"#!/bin/bash\necho 'No oracle solution written yet for {case.case_id}' >&2\nexit 1\n"
            )
