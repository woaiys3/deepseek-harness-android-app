#!/system/bin/sh
# 把移动端适配（mobile.css + mobile.js）注入 DSH 原生前端
# 不覆盖原生 index.html（避免 hash 失效），只在 </head> 前追加 mobile.css，
# 在 </body> 前追加 mobile.js。
# 用法：sh mobile-patch/inject.sh [dist目录]
set -e

PATCH_DIR="$(cd "$(dirname "$0")" && pwd)"
# 开发环境主目录（DSH 前端 dist 所在位置），可 export DSH_DEV_HOME 覆盖
H="${DSH_DEV_HOME:-/data/data/com.coomi.android/files/home}"
DIST="${1:-$H/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist}"

if [ ! -d "$DIST" ]; then
  echo "找不到 dist：$DIST" >&2
  exit 1
fi

# 1. 复制 mobile.css / mobile.js 到 dist
cp "$PATCH_DIR/mobile.css" "$DIST/mobile.css"
echo "  已复制 mobile.css"
cp "$PATCH_DIR/mobile.js" "$DIST/mobile.js"
echo "  已复制 mobile.js"

# 2. 注入引用（幂等）
if ! grep -q 'href="/mobile.css"' "$DIST/index.html" 2>/dev/null; then
  awk '{ if ($0 ~ /<\/head>/) { print "    <link rel=\"stylesheet\" href=\"/mobile.css\" />"; } print $0 }' \
    "$DIST/index.html" > "$DIST/index.html.tmp"
  mv "$DIST/index.html.tmp" "$DIST/index.html"
  echo "  已注入 mobile.css 引用"
else
  echo "  index.html 已含 mobile.css 引用，跳过"
fi

if ! grep -q 'src="/mobile.js"' "$DIST/index.html" 2>/dev/null; then
  awk '{ if ($0 ~ /<\/body>/) { print "    <script src=\"/mobile.js\"></script>"; } print $0 }' \
    "$DIST/index.html" > "$DIST/index.html.tmp"
  mv "$DIST/index.html.tmp" "$DIST/index.html"
  echo "  已注入 mobile.js 引用"
else
  echo "  index.html 已含 mobile.js 引用，跳过"
fi

# 3. viewport 补 maximum-scale / user-scalable=no（幂等）
# 为什么：DSH 原生 index.html 只声明 width=device-width, initial-scale=1，
# 没有禁止用户缩放 → 双指捏合能把整页缩放（正式版实测：预览窗存在时尤其明显）。
# WebView 的 setSupportZoom(false) 不足以禁掉 pinch，viewport 声明才是权威手段。
if grep -q 'user-scalable=no' "$DIST/index.html" 2>/dev/null; then
  echo "  viewport 已含 user-scalable=no，跳过"
else
  sed -i 's|content="width=device-width, initial-scale=1"|content="width=device-width, initial-scale=1, maximum-scale=1.0, user-scalable=no"|' "$DIST/index.html"
  if grep -q 'user-scalable=no' "$DIST/index.html"; then
    echo "  已补 viewport: maximum-scale=1.0, user-scalable=no"
  else
    echo "  !! viewport 未匹配（index.html 结构变了？请手工检查）" >&2
  fi
fi

echo "== 完成 =="
