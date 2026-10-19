#!/bin/sh
# run.sh: checks Rad Shields on your computer (needs a JDK). Fetches the public filter lists the first time,
# then answers 14 block-or-allow cases and times the matcher. Set LISTS=<folder> to use lists you already have.
set -e
here=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
[ -x "$JAVA_HOME/bin/javac" ] && PATH="$JAVA_HOME/bin:$PATH"
lists="${LISTS:-$here/lists}"
if [ ! -f "$lists/easylist.txt" ]; then
  mkdir -p "$lists"
  curl -sL -o "$lists/easylist.txt"       https://easylist.to/easylist/easylist.txt
  curl -sL -o "$lists/easyprivacy.txt"    https://easylist.to/easylist/easyprivacy.txt
  curl -sL -o "$lists/ublock.txt"         https://ublockorigin.github.io/uAssets/filters/filters.txt
  curl -sL -o "$lists/ublock-privacy.txt" https://ublockorigin.github.io/uAssets/filters/privacy.txt
  curl -sL -o "$lists/peterlowe.txt"      "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"
fi
out="$here/out"; rm -rf "$out"; mkdir -p "$out/com/zoro/rad"
cp "$here/../src/com/zoro/rad/Shields.java" "$out/com/zoro/rad/"
cp "$here/ShieldsTest.java" "$out/com/zoro/rad/"
javac -nowarn -d "$out/classes" "$out/com/zoro/rad/Shields.java" "$out/com/zoro/rad/ShieldsTest.java" 2>&1 | grep -v "^Note:" || true
mkdir -p "$out/run" && rm -rf "$out/run/lists" && ln -s "$lists" "$out/run/lists"
cd "$out/run" && java -cp "$out/classes" com.zoro.rad.ShieldsTest
rm -rf "$out"
