"""Shared evidence serialization and source snapshots for evaluation runners."""

import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest_files(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def candidate_patch(changed):
    """Keep each source path in the patch without exceeding Windows command-line limits."""
    return b"".join(subprocess.check_output(
        ["git", "diff", "--binary", "HEAD", "--", *changed[start:start + 32]], cwd=ROOT)
        for start in range(0, len(changed), 32))


def source_files_for(evaluation_files):
    """Include untracked source additions because Gradle compiles them too."""
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT).decode("utf-8").split("\0")
    source_files = {ROOT / name for name in names if name and
                    (("/src/" in name and not name.startswith("docs/")) or
                     name.endswith((".gradle", ".properties", ".snbt"))) and (ROOT / name).is_file()}
    source_files.update(evaluation_files)
    return sorted(source_files)
