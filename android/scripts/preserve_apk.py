"""Atomically retain the last successful APK, outside disposable build output."""
import hashlib
import json
import os
import shutil
import zipfile
from pathlib import Path


def preserve(root):
    source = root / "app/build/outputs/apk/debug/app-debug.apk"
    if not source.exists():
        return None
    with zipfile.ZipFile(source) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
            raise ValueError("Refusing to preserve an incomplete APK")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    destination = root / "artifacts"
    destination.mkdir(exist_ok=True)
    target = destination / "last-successful-debug.apk"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
        return target
    temporary = target.with_suffix(".tmp")
    shutil.copyfile(source, temporary)
    with temporary.open("rb") as stream:
        os.fsync(stream.fileno())
    temporary.replace(target)
    (destination / "last-successful-debug.sha256").write_text(f"{digest}  {target.name}\n")
    (destination / "last-successful-debug.json").write_text(json.dumps({
        "sha256": digest, "bytes": target.stat().st_size,
        "source_mtime": source.stat().st_mtime,
        "note": "Build artifact only; inspect the implementation plan for feature/test completeness.",
    }, indent=2) + "\n")
    print(f"Retained APK: {target} ({digest})")
    return target


if __name__ == "__main__":
    preserve(Path(__file__).resolve().parents[1])
