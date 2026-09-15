#!/bin/bash
# legacy 前端构建：把 Vite 6 现代产物转成老 WebView（Chromium 51+）可跑的单一 IIFE bundle。
# 做法：esbuild --bundle --format=iife --target=chrome51 把 index + vendor + langs 打成一个
#       非 module 脚本（老 WebView 不认 <script type="module">），并在头部注入 polyfill；
#       然后改写 index.html 用 <script defer> 加载（defer 必需，见第 3 步注释）。
# 幂等：重复运行会重新生成 index.legacy.js 并保持 index.html 只有一个普通 script 引用。
# 用法：sh build-legacy.sh <dsh-web-frontend/dist 目录>
set -e

DIST="${1:?用法: sh build-legacy.sh <dist目录>}"
PATCH_DIR="$(cd "$(dirname "$0")" && pwd)"
ESBUILD="$PATCH_DIR/esbuild.exe"

[ -d "$DIST/assets" ] || { echo "!! 找不到 $DIST/assets"; exit 1; }
[ -f "$ESBUILD" ] || { echo "!! 找不到 esbuild: $ESBUILD"; exit 1; }

# 1. 找到入口 chunk（index.html 里 <script type="module"> 引用的那个）
ENTRY=$(grep -oE 'src="[^"]*assets/index-[A-Za-z0-9_-]+\.js"' "$DIST/index.html" | head -1 | sed -E 's/src="([^"]*)"/\1/')
[ -n "$ENTRY" ] || { echo "!! index.html 里找不到 module 入口"; exit 1; }
ENTRY_ABS="$DIST/${ENTRY#/}"

echo "== legacy 前端构建 =="
echo "  入口: $ENTRY"

# 2. esbuild bundle：IIFE + chrome51 语法转译 + polyfill 注入 + minify
"$ESBUILD" "$ENTRY_ABS" \
  --bundle \
  --format=iife \
  --target=chrome51 \
  --minify \
  --banner:js="$(cat "$PATCH_DIR/polyfill.js")" \
  --outfile="$DIST/assets/index.legacy.js" \
  --log-level=warning

echo "  输出: assets/index.legacy.js ($(stat -c%s "$DIST/assets/index.legacy.js") bytes)"

# 3. 改写 index.html：module script + modulepreload → 普通 script；保留 CSS / favicon / mobile 引用
#    ⚠ 必须带 defer：<script type="module"> 天生 defer（等 body 解析完才执行），换成 head 里的普通
#    <script> 后会**立刻**执行，此时 <div id="root"> 还没被解析出来 → 前端抛
#    `Error: web app: missing #root` 并停在空白页（纯白 + 滚动条 + 无文字）。
#    v1.7.0~v1.11 的兼容版都漏了 defer，等于一直白屏。
awk '
  /<script type="module"/ { print "    <script defer src=\"/assets/index.legacy.js\"></script>"; next }
  /<link rel="modulepreload"/ { next }
  { print }
' "$DIST/index.html" > "$DIST/index.html.tmp"
mv "$DIST/index.html.tmp" "$DIST/index.html"

echo "== legacy 完成 =="
