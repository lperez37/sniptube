"""Atomically retain the last successful APK, outside disposable build output."""
import hashlib
import json
import os
import shutil
import zipfile
from pathlib import Path


def preserve(root, variant="debug"):
    if variant not in {"debug", "beta"}:
        raise ValueError("Unsupported APK variant")
    source = root / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    if not source.exists():
        return None
    with zipfile.ZipFile(source) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
            raise ValueError("Refusing to preserve an incomplete APK")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    destination = root / "artifacts"
    destination.mkdir(exist_ok=True)
    target = destination / f"last-successful-{variant}.apk"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
        return target
    temporary = target.with_suffix(".tmp")
    shutil.copyfile(source, temporary)
    with temporary.open("rb") as stream:
        os.fsync(stream.fileno())
    temporary.replace(target)
    (destination / f"last-successful-{variant}.sha256").write_text(f"{digest}  {target.name}\n")
    (destination / f"last-successful-{variant}.json").write_text(json.dumps({
        "sha256": digest, "bytes": target.stat().st_size,
        "source_mtime": source.stat().st_mtime,
        "note": "Build artifact only; inspect the implementation plan for feature/test completeness.",
    }, indent=2) + "\n")
    print(f"Retained APK: {target} ({digest})")
    return target


if __name__ == "__main__":
    for variant in ("debug", "beta"):
        preserve(Path(__file__).resolve().parents[1], variant)
