"""Copy the fixed LibRaw source subset without executing its build scripts or keeping a nested Git repo."""
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / ".cache" / "libraw-0.22.2"
DEST = ROOT / "third_party" / "libraw" / "upstream"
COMMIT = "b93f6e45c194f5df9b02a43b1af9a54b4f41f33f"


def main():
    actual = subprocess.check_output(["git", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True).strip()
    if actual != COMMIT:
        raise ValueError("LibRaw checkout does not match the pinned commit")
    if subprocess.check_output(["git", "-C", str(SOURCE), "status", "--porcelain"], text=True).strip():
        raise ValueError("LibRaw checkout contains local changes")
    files = []
    for folder in ("src", "internal", "libraw"):
        files.extend(path for path in (SOURCE / folder).rglob("*") if path.is_file())
    files.extend(SOURCE / name for name in ("COPYRIGHT", "LICENSE.LGPL", "LICENSE.CDDL", "README.md", "Makefile.am"))
    manifest = []
    for path in sorted(files):
        relative = path.relative_to(SOURCE)
        target = DEST / relative
        if target.exists() and target.read_bytes() != path.read_bytes():
            raise ValueError(f"Refusing to overwrite changed source: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        manifest.append({"path": relative.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    makefile = (SOURCE / "Makefile.am").read_text()
    block = makefile.split("lib_libraw_a_SOURCES =", 1)[1].split("\n\n", 1)[0]
    sources = re.findall(r"src/[\w/]+\.cpp", block)
    (DEST.parent / "sources.cmake").write_text(
        "# Source list copied from pinned upstream Makefile.am; no samples or test targets.\n"
        + "set(LIBRAW_SOURCES\n"
        + "".join(f'    "${{LIBRAW_ROOT}}/{path}"\n' for path in sources)
        + ")\n", encoding="utf-8", newline="\n")
    record = {"repository": "https://github.com/LibRaw/LibRaw", "tag": "0.22.2", "commit": COMMIT,
              "files": manifest, "source_bytes": sum(path.stat().st_size for path in files), "modifications": []}
    (DEST.parent / "source-manifest.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Copied {len(files)} source/license files, {record['source_bytes']} bytes; {len(sources)} C++ sources")


if __name__ == "__main__":
    main()
