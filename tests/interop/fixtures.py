"""Materialize deterministic fixtures; no SDK or network dependencies."""
import hashlib
import json
from pathlib import Path
import shutil
import sys


def generate(destination):
    out = Path(destination)
    out.mkdir(parents=True, exist_ok=True)
    (out / "text.txt").write_bytes("UniShare 世界\x00interop\n".encode())
    (out / "clipboard.txt").write_bytes("Manual clipboard 世界\n".encode())
    (out / "empty.bin").write_bytes(b"")
    (out / "large.bin").write_bytes(bytes(i % 251 for i in range(700001)))
    for name in ("image.png", "image.jpg"):
        shutil.copyfile(Path(__file__).parent / "fixtures" / name, out / name)
    kinds = [("text.txt", "text"), ("image.png", "image"), ("image.jpg", "image"),
             ("empty.bin", "file"), ("large.bin", "file"), ("clipboard.txt", "text")]
    manifest = [{"name": n, "kind": k, "size": (out/n).stat().st_size,
                 "sha256": hashlib.sha256((out/n).read_bytes()).hexdigest()} for n, k in kinds]
    expected = json.loads((Path(__file__).parent / "fixtures/manifest.json").read_text())
    if manifest != expected:
        raise ValueError("fixture bytes differ from committed SHA-256 expectations")
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    generate(sys.argv[1])
