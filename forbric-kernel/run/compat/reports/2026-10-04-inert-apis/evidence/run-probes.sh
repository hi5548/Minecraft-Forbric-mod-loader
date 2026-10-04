#!/usr/bin/env bash
# Reproduce the two offline probes that gate the 1.21.1 Indigo and sound retargets.
# No game JVM: real remapped guest bytes x real merged base bytes, frame-verified with ASM's SimpleVerifier
# over the full game classpath (the check a shape-only probe lacks — see the Indigo 55164ff3 revert).
#
# Usage: run-probes.sh <forbric-root> <kernel-dir> <remap-cache-dir>
#   forbric-root    e.g. /Users/.../实验/forbric   (p0/stage-1.21.1, p0/mc-1.21.1 under it)
#   kernel-dir      a kernel checkout whose build/classes/java/main is current (gradlew compileJava)
#   remap-cache-dir a harness remap cache holding fabric-renderer-indigo-*.jar / fabric-sound-api-v1-*.jar
set -euo pipefail
ROOT=${1:?usage: run-probes.sh <forbric-root> <kernel-dir> <remap-cache-dir>}
KERNEL=${2:?}
CACHE=${3:?}
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}
JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
ASM=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm" \( -name 'asm-9.*.jar' -o -name 'asm-tree-9.*.jar' -o -name 'asm-analysis-9.*.jar' \) | sort -u | tr '\n' ':' | sed 's/:$//')
MIXIN=$(find "$HOME/.gradle/caches/modules-2/files-2.1/net.fabricmc/sponge-mixin" -name '*.jar' | head -1)
STAGE="$ROOT/p0/stage-1.21.1"; MC="$ROOT/p0/mc-1.21.1"
MERGED="$STAGE/merged-base/patched-mc-merged-1.21.1.jar"
python3 - "$MC" > "$HERE/game-cp.txt" <<'PY'
import json,sys
from pathlib import Path
mc=Path(sys.argv[1])
data=json.loads((mc/"versions/1.21.1/1.21.1.json").read_text())
out=[]
for lib in data.get("libraries",[]):
    parts=lib.get("name","").split(":")
    if len(parts)<3: continue
    g,art,ver=parts[0].replace(".","/"),parts[1],parts[2]
    cls=("-"+parts[3]) if len(parts)>3 else ""
    j=mc/"libraries"/g/art/ver/f"{art}-{ver}{cls}.jar"
    if j.exists(): out.append(str(j))
print(":".join(out))
PY
GAME="$MERGED:$STAGE/neoforge-runtime/neoforge-runtime.jar:$STAGE/merged-base/forge-runtime-interop.jar:$(cat "$HERE/game-cp.txt")"
INDIGO=$(ls "$CACHE"/fabric-renderer-indigo-*.jar | head -1)
SOUND=$(ls "$CACHE"/fabric-sound-api-v1-*.jar | head -1)
rm -rf "$HERE/classes"; mkdir -p "$HERE/classes"
"$JAVAC" -proc:none -cp "$ASM:$MIXIN:$KERNEL/build/classes/java/main" -d "$HERE/classes" \
  "$HERE/IndigoRetargetProbe.java" "$HERE/SoundRetargetProbe.java"
echo "== Indigo =="
"$JAVA" -cp "$ASM:$MIXIN:$KERNEL/build/classes/java/main:$HERE/classes" \
  net.forbric.kernel.mixin.IndigoRetargetProbe "$MERGED" "$INDIGO" "$GAME"
echo "== Sound =="
"$JAVA" -cp "$ASM:$MIXIN:$KERNEL/build/classes/java/main:$HERE/classes" \
  net.forbric.kernel.mixin.SoundRetargetProbe "$MERGED" "$SOUND" "$GAME"
