#!/usr/bin/env bash
# 用 IDEA 2026.2 自带的描述符加载器探测某个插件包能否被平台接受。
# 用法: bash probe.sh build/WorkNotes-0.2.0.jar
IDEA="C:/Users/FuXin/AppData/Local/Programs/IntelliJ IDEA"
cd "$(dirname "$0")"
mkdir -p build/probe

javac -encoding UTF-8 -cp "$IDEA/lib/*;$IDEA/lib/modules/*" -d build/probe probe/Probe.java || exit 1

for f in "$@"; do
  echo "=============== $f"
  "$IDEA/jbr/bin/java.exe" -Dfile.encoding=UTF-8 -Didea.home.path="$IDEA" \
    --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
    --add-exports java.base/sun.nio.fs=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    -cp "build/probe;$IDEA/lib/*;$IDEA/lib/modules/*" Probe "$f" 2>&1 \
    | grep -a -v "Picked up" | head -25
done
