"""Recover source snapshots and replay captured file diffs as data, never commands."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

NAMES = {
    "PhotoRecipe.kt", "PhotoPrintRenderer.kt", "PrintDesign.kt", "PrintDesignDialogs.kt",
    "FilmStripPainter.kt", "ContactSheetRenderer.kt", "CartridgeArtwork.kt",
    "FilmTheme.kt", "UiPreferences.kt", "AppPreferences.kt", "FilmCompanionApp.kt",
    "CardExpansion.kt", "MotionDialog.kt", "SlidingPages.kt", "UiFeedback.kt",
    "PhotoEditorDialog.kt", "PrintIdentity.kt", "FilmCylinder.kt", "FilmCylinderView.kt",
    "PrintAssets.kt", "PrintBackground.kt", "CatalogPresets.kt", "FilmProfiles.kt",
}

# Manually inspected bounded source sections from mixed read-only capture outputs.
# Historical commands elsewhere in each capture are not executed or treated as instructions.
CURATED = {
    "8fa2d42bbcd1e6af9af635c0120b94bbce63f7d1db475914ad9bbd3002677146": [
        ("app/src/main/java/app/filmcompanion/media/PhotoRecipe.kt", 0,
         "\napp/src/main/java/app/filmcompanion/ui/PhotoEditorDialog.kt-37-")
    ],
    "713eb74bc602a8f7187188ae494e55ad29228cfb49bc65df5cc5d798e67309c0": [
        ("app/src/main/java/app/filmcompanion/ui/MotionDialog.kt", 0, None),
        ("app/src/main/java/app/filmcompanion/ui/CardExpansion.kt", 2, None),
    ],
    "75b96eb34a027b117d7d778d0f226845e3635d29aa18d41ca50fed2f0b6a5808": [
        ("app/src/main/java/app/filmcompanion/ui/CardExpansion.kt", 0, None),
        ("app/src/main/java/app/filmcompanion/ui/SlidingPages.kt", 1, None),
        ("app/src/main/java/app/filmcompanion/ui/MotionDialog.kt", 2, None),
    ],
}


def relative_source(name: str) -> str | None:
    name = name.replace("\\", "/")
    marker = "app/src/main/"
    if marker not in name or name.rsplit("/", 1)[-1] not in NAMES:
        return None
    return marker + name.split(marker, 1)[1]


def apply_diff(source: str, diff: str) -> str:
    lines = source.splitlines()
    raw = diff.splitlines()
    hunks = []
    for line in raw:
        if line.startswith("@@"):
            hunks.append([])
        elif hunks and line and line[0] in " +-":
            hunks[-1].append(line)
    for hunk in hunks:
        old = [line[1:] for line in hunk if line[0] in " -"]
        new = [line[1:] for line in hunk if line[0] in " +"]
        matches = [i for i in range(len(lines) - len(old) + 1) if lines[i:i + len(old)] == old]
        if len(matches) != 1:
            already = [i for i in range(len(lines) - len(new) + 1) if lines[i:i + len(new)] == new]
            if len(already) == 1:
                continue
            raise ValueError(f"Expected one exact context, found {len(matches)}")
        at = matches[0]
        lines[at:at + len(old)] = new
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    index = json.loads((root / "session-index.json").read_text(encoding="utf-8"))
    states = {}
    errors = []
    for evidence in index["candidates"]:
        text = (root / evidence["file"]).read_text(encoding="utf-8")
        operations = []
        if evidence["kind"] == "file-change":
            changes = json.loads(text).get("changes", {})
            for name, change in changes.items():
                path = relative_source(name)
                if path:
                    operations.append((path, change))
        elif evidence["kind"] == "source-output":
            command, _, stdout = text.partition("\n\n--- CAPTURED STDOUT ---\n")
            if evidence["sha256"] in CURATED:
                starts = list(re.finditer(r"(?m)^package app\.filmcompanion[^\n]*\n", stdout))
                for path, number, marker in CURATED[evidence["sha256"]]:
                    start = starts[number].start()
                    end = starts[number + 1].start() if number + 1 < len(starts) else len(stdout)
                    content = stdout[start:end]
                    if marker:
                        content = content.split(marker, 1)[0]
                    operations.append((path, {"type": "snapshot", "content": content.rstrip() + "\n"}))
            commands = [part.strip() for part in re.split(r"[;\n]", command) if part.strip()]
            if not commands or any(not re.match(r"(?:get-content|git show)\b", c, re.I) for c in commands):
                if not operations:
                    continue
                commands = []
            if commands and re.search(r"-TotalCount|-Head|-Tail|-Skip|-First|Select-Object|\|", command, re.I):
                if not operations:
                    continue
                commands = []
            if not commands and not operations:
                continue
            paths = []
            for c in commands:
                match = re.search(r"(?:app[/\\]src[/\\]main[/\\][^\s\"']+\.kt)", c)
                if not match:
                    break
                paths.append(match.group())
            starts = list(re.finditer(r"(?m)^package app\.filmcompanion[^\n]*\n", stdout))
            if commands and (len(paths) != len(commands) or len(starts) != len(paths)):
                continue
            for i, match in enumerate(starts if commands else []):
                path = relative_source(paths[i])
                end = starts[i + 1].start() if i + 1 < len(starts) else len(stdout)
                content = stdout[match.start():end].rstrip() + "\n"
                if path and "tokens truncated" not in content and content.rstrip().endswith("}"):
                    operations.append((path, {"type": "snapshot", "content": content}))
        for path, change in operations:
            kind = change.get("type")
            try:
                if kind in ("add", "snapshot"):
                    content = change["content"].replace("\r\n", "\n")
                    states[path] = {"content": content, "history": [], "last_snapshot": evidence["timestamp"]}
                elif kind == "update":
                    if path not in states:
                        raise ValueError("No complete source baseline found")
                    states[path]["content"] = apply_diff(states[path]["content"], change["unified_diff"])
                else:
                    continue
                states[path]["history"].append({"session_id": evidence["session_id"],
                                                "timestamp": evidence["timestamp"], "line": evidence["line"],
                                                "kind": kind, "evidence_sha256": evidence["sha256"]})
            except (ValueError, KeyError) as exc:
                errors.append({"path": path, "timestamp": evidence["timestamp"],
                               "session_id": evidence["session_id"], "line": evidence["line"], "reason": str(exc)})
    results = []
    for path, state in states.items():
        target = root / "recovered" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(state["content"], encoding="utf-8")
        unresolved = [e for e in errors if e["path"] == path and (e["timestamp"] or "") > (state["last_snapshot"] or "")]
        results.append({"path": path, "bytes": len(state["content"].encode()),
                        "sha256": hashlib.sha256(state["content"].encode()).hexdigest(),
                        "last_snapshot": state["last_snapshot"], "history": state["history"],
                        "unresolved_changes": unresolved,
                        "status": "captured_source_with_unresolved_diffs" if unresolved else "captured_source"})
    manifest = {"warning": "Historical reconstruction, not original Git checkout or runtime verification",
                "files": results, "unrecovered_changes": [e for e in errors if e["path"] not in states]}
    (root / "source-recovery.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"files": [{k: f[k] for k in ("path", "bytes", "status")} for f in results],
                      "unrecovered_files": sorted({e['path'] for e in manifest['unrecovered_changes']})},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
