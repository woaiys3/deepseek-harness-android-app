#!/system/bin/sh
# 把 dsh-patches/overlay 下的补丁文件覆盖回 dshroot
# 用途：更新 DSH（npm 重装）后，重新应用 Android 所需的源码补丁 + Shizuku 插件。
# 前端 = DSH 原生界面 + 移动端适配（mobile-patch/，由 android-app/build.sh 打包时注入）。
# 用法：sh dsh-patches/apply.sh
set -e
# 开发环境主目录，可 export DSH_DEV_HOME 覆盖
H="${DSH_DEV_HOME:-/data/data/com.coomi.android/files/home}"
SRC="$H/dsh-patches/overlay/lib"
DST="$H/dshroot/lib"
RUNTIME="$H/runtime"
NODE="$RUNTIME/bin/node"

if [ ! -d "$SRC" ]; then
  echo "找不到补丁目录：$SRC" >&2
  exit 1
fi
if [ ! -d "$DST" ]; then
  echo "找不到 dshroot：$DST" >&2
  exit 1
fi

echo "== 应用补丁 overlay -> dshroot =="
( cd "$SRC" && tar cf - . ) | ( cd "$DST" && tar xf - )

export LD_LIBRARY_PATH="$RUNTIME/lib"
export DSHROOT_PKG="$DST/node_modules/@deepseek-ai/dsh/package.json"

echo "== 把 Shizuku 插件加入 dsh package.json 依赖（幂等）=="
"$NODE" -e '
const fs = require("fs");
const p = process.env.DSHROOT_PKG;
const m = JSON.parse(fs.readFileSync(p, "utf8"));
m.dependencies = m.dependencies || {};
if (!m.dependencies["@deepseek-ai/dsh-tool-shizuku"]) {
  m.dependencies["@deepseek-ai/dsh-tool-shizuku"] = "0.1.0";
  fs.writeFileSync(p, JSON.stringify(m, null, 2) + "\n");
  console.log("  已添加 @deepseek-ai/dsh-tool-shizuku 依赖");
} else {
  console.log("  依赖已存在，跳过");
}
'

echo "== 语法自检（6 个 JS 补丁）=="
for f in \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-model-selection/lib/client.js" ; do
  if [ -f "$f" ]; then
    if "$NODE" --check "$f" 2>/dev/null; then
      echo "  OK: ${f##*/dshroot/lib/}"
    else
      echo "  FAIL: ${f##*/dshroot/lib/}" >&2
      exit 1
    fi
  else
    echo "  MISSING: $f" >&2
    exit 1
  fi
done

echo "== 完成 =="
