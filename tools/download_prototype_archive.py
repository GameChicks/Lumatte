"""Manual ranged HTTPS download for the prototype tool cache (never builds or tests)."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name", choices=("jdk17.zip", "gradle-8.13-bin.zip"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent / ".cache" / "android-prototype"
    manifest = json.loads((root / "tool-downloads.json").read_text(encoding="utf-8-sig"))
    if args.name == "jdk17.zip":
        url, size, checksum = manifest["jdkUrl"], manifest.get("jdkSize", 190817615), manifest["jdkSha256"]
    else:
        url, size, checksum = "https://services.gradle.org/distributions/gradle-8.13-bin.zip", 136983045, manifest["gradleSha256"]
    if not url.startswith("https://") or root.drive.lower() != "d:":
        raise ValueError("HTTPS and the isolated D: cache are required")
    archive = root / "downloads" / args.name
    offset = archive.stat().st_size if archive.exists() else 0
    if offset > size:
        raise ValueError("Unexpected partial archive size")
    if offset == size:
        if hashlib.file_digest(archive.open("rb"), "sha256").hexdigest() != checksum:
            raise ValueError("Archive checksum mismatch")
        print(f"Verified {args.name}", flush=True)
        return
    chunk_size = (size - offset + 7) // 8
    ranges = [(start, min(start + chunk_size, size) - 1) for start in range(offset, size, chunk_size)]

    def download(bounds):
        start, end = bounds
        part = archive.with_name(f"{args.name}.{start}-{end}.part")
        expected = end - start + 1
        if not part.exists() or part.stat().st_size != expected:
            subprocess.run(["curl.exe", "--silent", "--show-error", "--fail", "--location",
                            "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "30",
                            "--speed-time", "60", "--speed-limit", "1024", "--retry", "4",
                            "--range", f"{start}-{end}", "--output", str(part), url], check=True)
        if part.stat().st_size != expected:
            raise ValueError("Server did not honor the requested range")
        print(f"{args.name}: received range {start}-{end}", flush=True)
        return part

    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(download, bounds): bounds for bounds in ranges}
        parts = {futures[f]: f.result() for f in as_completed(futures)}
    assembled = archive.with_suffix(".assembled")
    with assembled.open("wb") as output:
        if offset:
            with archive.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)
        for bounds in ranges:
            with parts[bounds].open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)
    with assembled.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != checksum:
        raise ValueError("Assembled archive checksum mismatch; partial originals preserved")
    assembled.replace(archive)
    print(f"Verified {args.name}: {size} bytes", flush=True)


if __name__ == "__main__":
    main()
