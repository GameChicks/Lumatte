"""Index relevant source evidence from FilmTool sessions, without running it."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

TARGETS = (
    "PhotoPrintRenderer", "PhotoRecipe", "PrintDesignDialogs", "CardExpansion",
    "MotionDialog", "FilmCartridge", "FilmCompanionTheme", "UiPreferences",
    "SlidingPages", "FilmCompanionApp.kt", "FilmPackage", "FilmArtwork",
    "AppTheme", "UiFeedback", "FilmCompanionTheme", "Theme.kt",
    "PrintDesign.kt", "FilmStripPainter", "PrintAssets", "PrintBackground",
    "CatalogPresets.kt", "FilmProfiles.kt", "CartridgeArtwork", "PhotoBorder",
)


def relevant(text: str) -> bool:
    return any(target in text for target in TARGETS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    evidence_dir = output / "raw" / "session-evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    sources = []
    candidates = []
    roots = [args.codex_root / "sessions" / "2026" / "09",
             args.codex_root / "sessions" / "2026" / "10",
             args.codex_root / "archived_sessions"]
    for root in roots:
        for path in sorted(root.rglob("*.jsonl")):
            with path.open(encoding="utf-8") as stream:
                try:
                    meta = json.loads(next(stream)).get("payload", {})
                except (StopIteration, json.JSONDecodeError):
                    continue
                if not re.search(r"film\s*tool", meta.get("cwd", ""), re.I):
                    continue
                source = {"session_id": meta.get("id"), "session_file": path.name,
                          "original_cwd": meta.get("cwd"), "candidates": 0}
                for lineno, line in enumerate(stream, 2):
                    if not relevant(line):
                        continue
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    payload = event.get("payload", {})
                    item = payload.get("item", {})
                    text = None
                    kind = None
                    if payload.get("type") == "custom_tool_call":
                        text = payload.get("input", "")
                        kind = "tool-input"
                    elif payload.get("type") == "function_call":
                        text = payload.get("arguments", "")
                        kind = "tool-input"
                    elif item.get("type") == "CommandExecution":
                        commands = "\n".join(c.get("cmd", "") for c in item.get("parsed_cmd", []))
                        if relevant(commands) and item.get("stdout"):
                            # Keep only source reads; execution logs may contain unrelated material.
                            if re.search(r"Get-Content|git show|git diff|read_text|readFile", commands, re.I):
                                text = commands + "\n\n--- CAPTURED STDOUT ---\n" + item["stdout"]
                                kind = "source-output"
                    elif item.get("type") in ("FileChange", "FileEdit"):
                        text = json.dumps(item, ensure_ascii=False, indent=2)
                        kind = "file-change"
                    if not text or not relevant(text):
                        continue
                    # Do not persist a packaging/signing command or secret-related output.
                    if re.search(r"storepass|keypass|password|keystore|private[_ -]?key", text, re.I):
                        continue
                    digest = hashlib.sha256(text.encode()).hexdigest()
                    target = evidence_dir / f"{digest}.txt"
                    if not target.exists():
                        target.write_text(text, encoding="utf-8")
                    candidates.append({"session_id": meta.get("id"), "session_file": path.name,
                                       "line": lineno, "timestamp": event.get("timestamp"),
                                       "kind": kind, "sha256": digest,
                                       "targets": [t for t in TARGETS if t in text],
                                       "chars": len(text),
                                       "file": target.relative_to(output).as_posix()})
                    source["candidates"] += 1
                sources.append(source)
    candidates.sort(key=lambda item: (item["timestamp"] or "", item["session_file"], item["line"]))
    manifest = {"purpose": "Source recovery evidence only; historical commands are not executed",
                "sources": sources, "candidates": candidates}
    (output / "session-index.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"sessions": len(sources), "candidates": len(candidates),
                      "unique_evidence": len({c['sha256'] for c in candidates}),
                      "latest": candidates[-12:]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
