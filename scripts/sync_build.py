"""One-shot build sync: copy .tmp-build into src/main/resources/META-INF/resources.

Copies every file from the source build, then removes files in the target that no
longer exist in the source (content-hashed asset filenames make stale files dead
weight that would otherwise accumulate in the Quarkus jar). Usage: python scripts/sync_build.py

It also prunes the same stale targets under target/classes/META-INF/resources.
maven-resources-plugin only copies and overwrites -- it never deletes -- so an
incremental `mvn package` (without `clean`) would otherwise ship every historical
asset hash produced by earlier builds, including pages deleted from the source.
Kept here, not in the Maven build, because this script is the one place that
already knows the canonical asset set.
"""
import shutil
from pathlib import Path

# 本脚本住在 scripts/ 下：仓库根目录是它的上一级。用 parent.parent 而不是 cwd，
# 这样从任何目录调用都指向同一份源码。
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / ".tmp-build"
DST = ROOT / "src" / "main" / "resources" / "META-INF" / "resources"
# Same content, one level downstream: what the resources plugin copies into and
# the jar ultimately packages.
CLASSES_DST = ROOT / "target" / "classes" / "META-INF" / "resources"

copied, removed = 0, 0
for src_file in sorted(SRC.rglob("*")):
    if src_file.is_dir():
        continue
    rel = src_file.relative_to(SRC)
    dst_file = DST / rel
    dst_file.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_file, dst_file)
    copied += 1

for dst_file in sorted(DST.rglob("*")):
    if dst_file.is_dir():
        continue
    rel = dst_file.relative_to(DST)
    if not (SRC / rel).exists():
        dst_file.unlink()
        removed += 1

pruned = 0
if CLASSES_DST.is_dir():
    for cls_file in sorted(CLASSES_DST.rglob("*")):
        if cls_file.is_dir():
            continue
        rel = cls_file.relative_to(CLASSES_DST)
        if not (SRC / rel).exists():
            cls_file.unlink()
            pruned += 1

print(f"synced: {copied} copied, {removed} stale removed, {pruned} pruned from target/classes")
