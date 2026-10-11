"""Manual SDK archive recovery after sdkmanager reports a truncated download."""
import argparse
import hashlib
from pathlib import Path
import shutil
from xml.dom import minidom
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", choices=("ndk;28.2.13676358", "platforms;android-36", "build-tools;36.0.0"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent / ".cache" / "android-prototype"
    document = minidom.parse(str(root / "repository.xml"))
    package = next(p for p in document.getElementsByTagName("remotePackage") if p.getAttribute("path") == args.package)
    archives = package.getElementsByTagName("archive")
    selected = next(a for a in archives if not a.getElementsByTagName("host-os") or
                    a.getElementsByTagName("host-os")[0].firstChild.data == "windows")
    complete = selected.getElementsByTagName("complete")[0]
    filename = complete.getElementsByTagName("url")[0].firstChild.data
    checksum = complete.getElementsByTagName("checksum")[0].firstChild.data
    archive = root / "downloads" / filename
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "sha1").hexdigest() != checksum:
            raise ValueError("Official SDK archive checksum mismatch")
    destination = (root / "sdk" / args.package.replace(";", "/")).resolve()
    if not destination.is_relative_to(root.resolve()) or destination.drive.lower() != "d:":
        raise ValueError("SDK must remain in the D: prototype cache")
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            pieces = entry.filename.replace("\\", "/").split("/")
            if len(pieces) < 2:
                continue
            target = destination.joinpath(*pieces[1:]).resolve()
            if not target.is_relative_to(destination):
                raise ValueError("Invalid SDK member path")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as data, target.open("wb") as output:
                    shutil.copyfileobj(data, output, 1024 * 1024)
    # Preserve the official schema namespaces, revision and license reference.
    local_document = minidom.Document()
    repository = local_document.importNode(document.documentElement, deep=False)
    local_document.appendChild(repository)
    referenced_licenses = {node.getAttribute("ref") for node in package.getElementsByTagName("uses-license")}
    for license_node in document.getElementsByTagName("license"):
        if license_node.getAttribute("id") in referenced_licenses:
            repository.appendChild(local_document.importNode(license_node, deep=True))
    local = local_document.createElement("localPackage")
    local.setAttribute("path", args.package)
    for child in package.childNodes:
        if child.nodeType == child.ELEMENT_NODE and child.tagName in (
                "type-details", "revision", "display-name", "uses-license", "dependencies"):
            local.appendChild(local_document.importNode(child, deep=True))
    repository.appendChild(local)
    (destination / "package.xml").write_bytes(local_document.toxml(encoding="UTF-8"))
    print(f"Installed checksum-verified official archive: {args.package}", flush=True)


if __name__ == "__main__":
    main()
