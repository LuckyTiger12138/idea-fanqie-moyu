#!/usr/bin/env bash
# 离线构建：直接用本机已安装 IDEA 的平台 jar 编译，不联网、不需要 Gradle。
set -euo pipefail

cd "$(dirname "$0")"

# 编译基线：2021.3（213）的安装目录。可用环境变量覆盖。
IDEA_HOME="${IDEA_HOME:-C:/Users/FuXin/AppData/Local/Programs/IntelliJ IDEA Ultimate 3}"

if [ ! -d "$IDEA_HOME/lib" ]; then
  echo "找不到 IDEA 目录：$IDEA_HOME" >&2
  echo "请设置 IDEA_HOME 环境变量指向 IDEA 安装根目录" >&2
  exit 1
fi

OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

CP=""
for j in "$IDEA_HOME"/lib/*.jar; do
  CP="${CP}$(cygpath -w "$j");"
done
CP="${CP%;}"

find src/main/java -name '*.java' > "$OUT/sources.txt"
echo "源文件数量：$(wc -l < "$OUT/sources.txt")"
echo "编译基线：$(cat "$IDEA_HOME/build.txt" 2>/dev/null || echo unknown)"

javac -encoding UTF-8 -nowarn --release 11 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"

cp -r src/main/resources/. "$OUT/classes/"

# 输出 .jar 而不是 .zip：IDEA 2026.x 对 .zip 走解压式加载会失败，
# 对 .jar 直接读 zip 条目，才能正确解析 META-INF/plugin.xml。
PKG="$OUT/WorkNotes-0.3.4.jar"
( cd "$OUT/classes" && jar --create --file "../WorkNotes-0.3.4.jar" . )

echo "构建完成：$(pwd)/$PKG"
ls -la "$PKG"
