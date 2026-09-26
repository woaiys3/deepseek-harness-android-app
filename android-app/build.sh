#!/system/bin/sh
# DeepSeek Harness 手机版 APK 构建脚本
# 需要开发环境（runtime/dshroot/.dsh 等），路径可通过环境变量覆盖
set -e
# 开发环境主目录（runtime/dshroot/.dsh 所在位置）
H="${DSH_DEV_HOME:-/data/data/com.coomi.android/files/home}"
source "$H/build/env.sh"

# 本脚本所在目录（android-app/）
P="$(cd "$(dirname "$0")" && pwd)"
# android.jar 放 android-app/sdk/（可 export ANDROID_JAR 覆盖）
AJ="${ANDROID_JAR:-$P/sdk/android.jar}"
# javac 所在目录：优先 JAVA_BIN，否则从 DSH_DEV_HOME 推导（devhome 与 usr 同在 buildenv 下）
JAVA="${JAVA_BIN:-$H/../usr/lib/jvm/java-17-openjdk/bin}"
[ -x "$JAVA/javac" ] || { echo "!! 找不到 javac：$JAVA/javac（请 export JAVA_BIN=.../java-17-openjdk/bin）"; exit 1; }
# classpath 分隔符：Windows(Git Bash) 用 ';'，POSIX/Android 用 ':'（Windows 的 Java 程序不认 ':' 分隔）
# Windows 上 d8/apksigner 是 .bat（bash 不会自动补 .bat 后缀，显式指定）
CP_SEP=":"
D8="d8"
APKSIGNER="apksigner"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";"; D8="d8.bat"; APKSIGNER="apksigner.bat" ;;
esac
# 签名密钥（自行准备，不入仓库）
KEY="$P/release.jks"

echo "== 0/7 组装 payload =="
# 移动端适配注入（mobile.css，不覆盖原生 index.html，DSH 更新后也自动重新注入）
sh "$P/../mobile-patch/inject.sh"

rm -rf "$P/staging" "$P/out" "$P/assets"
mkdir -p "$P/staging/runtime/bin" "$P/staging/runtime/lib" \
         "$P/staging/runtime/etc" \
         "$P/staging/bin" "$P/staging/dshroot" \
         "$P/staging/dshhome/profiles/web" "$P/assets"

# Termux 共存修复（v1.7.4）：内置 node 在 Termux 环境编译，OPENSSLDIR 编译死为
# /data/data/com.termux/files/usr。装了 Termux 的设备读其 openssl.cnf 触发 EACCES，
# node 启动即崩；没装 Termux 时靠 ENOENT 静默才碰巧正常。App 启动引擎时注入
# OPENSSL_CONF 指向本文件（最小配置，显式激活内置 default provider，无需外部模块）。
cat > "$P/staging/runtime/etc/openssl.cnf" <<'EOF'
openssl_conf = openssl_init

[openssl_init]
providers = provider_sect

[provider_sect]
default = default_sect

[default_sect]
activate = 1
EOF

cp -L "$H/runtime/bin/node" "$P/staging/runtime/bin/node"
# Android 内置 ripgrep（grep/glob 工具用，@vscode/ripgrep 无 android 平台包）：
# rg 由 dsh-tool-fs-search 在 @vscode/ripgrep 解析失败后回退查找（runtime/bin/rg）
if [ -f "$H/runtime/bin/rg" ]; then
  cp -L "$H/runtime/bin/rg" "$P/staging/runtime/bin/rg"
  chmod +x "$P/staging/runtime/bin/rg"
  echo "  内置 rg: runtime/bin/rg"
# Android 内置 curl（AI 的 bash 工具用，Android 系统不带 curl）：
# termux 静态构建（NDK r29，interpreter /system/bin/linker64），依赖 libcurl/libnghttp2/3/ngtcp2/libssh2/openssl
if [ -f "$H/runtime/bin/curl" ]; then
  cp -L "$H/runtime/bin/curl" "$P/staging/runtime/bin/curl"
  chmod +x "$P/staging/runtime/bin/curl"
  echo "  内置 curl: runtime/bin/curl"
fi
fi

for f in $(find "$H/runtime/lib" -maxdepth 1 -type f); do
  cp -L "$f" "$P/staging/runtime/lib/"
done

cat > "$P/staging/runtime/lib/LINKS.txt" <<'EOF'
libcrypto.so	libcrypto.so.3
libicudata.so	libicudata.so.78.3
libicudata.so.78	libicudata.so.78.3
libicui18n.so	libicui18n.so.78.3
libicui18n.so.78	libicui18n.so.78.3
libicuio.so	libicuio.so.78.3
libicuio.so.78	libicuio.so.78.3
libicutest.so	libicutest.so.78.3
libicutest.so.78	libicutest.so.78.3
libicutu.so	libicutu.so.78.3
libicutu.so.78	libicutu.so.78.3
libicuuc.so	libicuuc.so.78.3
libicuuc.so.78	libicuuc.so.78.3
libsqlite3.so	libsqlite3.so.3.53.4
libsqlite3.so.0	libsqlite3.so.3.53.4
libssl.so	libssl.so.3
libz.so	libz.so.1.3.2
libz.so.1	libz.so.1.3.2
EOF

# 补全 soname 为实体文件（关键修复）：
# jar 打包会把符号链接压平成普通内容，且部分设备解压后无法创建软链接（FUSE/权限），
# 导致 node 启动报 "library libz.so.1 not found"。这里直接把链接目标复制成同名实体文件，
# 动态加载器按名字找文件即可，不依赖任何链接支持。
cd "$P/staging/runtime/lib"
while IFS=$'\t' read -r _link _target; do
  case "$_link" in ""|\#*) continue ;; esac
  [ -n "$_target" ] || continue
  if [ ! -e "$_link" ] && [ -f "$_target" ]; then
    cp -L "$_target" "$_link"
    echo "  soname 实体化: $_link"
  fi
done < LINKS.txt
cd - >/dev/null

mkdir -p "$P/staging/dshroot/lib"
# 两个排除项：
#   1) dsh 的 node_modules/.bin（原脚本即排除）
#   2) dsh/node_modules/@deepseek-ai/dsh —— 开发树里被误造的「dsh 自我递归嵌套」
#      （实测 632 层、路径约 19000 字符）。tar 的 --exclude 会在深入前跳过它，
#      构建产物不需要它，复制耗时也从「卡死」降到数分钟。
# ⚠ 不要在这里改用 robocopy：它遇到这棵病态目录会不返回（实测挂住）或报错 16。
( cd "$H/dshroot/lib" && tar cf - --exclude='./node_modules/@deepseek-ai/dsh/node_modules/.bin' --exclude='./node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh' . ) \
  | ( cd "$P/staging/dshroot/lib" && tar xf - )

# dshroot 版本标记：App 用它判断「外部 /sdcard/DeepSeekHarness/dshroot」是否需要补齐。
# 外部已有的文件永不覆盖（保留 AI 运行时修改），缺失文件才从 APK 补上。
DSHROOT_REV="$(date +%Y%m%d%H%M%S)"
echo "$DSHROOT_REV" > "$P/staging/dshroot/REVISION"
echo "$DSHROOT_REV" > "$P/assets/dshroot_revision.txt"

# 内核树布局标记：布局本身变了（如依赖树结构换了）必须全量重推 dshroot。
# 否则同内核升级走 fast 同步（只补白名单文件），整棵树仍是旧结构，修好的补丁包不生效。
DSHROOT_LAYOUT="hoisted-1"
echo "$DSHROOT_LAYOUT" > "$P/assets/dshroot_layout.txt"

# 内核版本标记（v1.5.2 慢启动修复）：REVISION 是构建时间戳，每次构建都变；
# App 用它区分「同内核升级（内容几乎不变，快速同步即可）」与「内核升级（新增文件，需全量补齐）」。
DSHROOT_PKG_JSON="$P/staging/dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
if [ -f "$DSHROOT_PKG_JSON" ]; then
  grep -m1 '"version"' "$DSHROOT_PKG_JSON" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' > "$P/assets/dshroot_kernel_version.txt"
  echo "  内核版本标记: $(cat "$P/assets/dshroot_kernel_version.txt")"
else
  echo "  !! 未找到 dsh/package.json，内核版本标记留空（App 将保守全量补齐）"
  : > "$P/assets/dshroot_kernel_version.txt"
fi

cat > "$P/staging/bin/bash" <<'EOF'
#!/system/bin/sh
exec /system/bin/sh "$@"
EOF
chmod +x "$P/staging/bin/bash"
# 内置 pnpm（v1.15.3）：插件管理的「添加插件」全程 pnpm add，而 payload 原本不带 pnpm → 退出码 127，
# 三种输入（本地目录 / 包名 / GitHub 地址）全装不上。这里把 pnpm 的纯 JS bundle 一起打进 payload：
#   payload/bin/pnpm  ← wrapper（payload/bin 在引擎 PATH 上，所以插件管理器能直接找到 pnpm）
#   payload/pnpm/     ← pnpm 本体（bin/pnpm.cjs + dist/pnpm.cjs 等）
# 取舍：不带 dist/node_modules（实测安装流程不依赖它，省 9MB）；不带 *.node
#      （本项目 payload 红线=不带原生模块，且 pnpm 只提供 darwin/win32 的）。
if [ -d "$H/pnpm" ]; then
  rm -rf "$P/staging/pnpm"
  cp -r "$H/pnpm" "$P/staging/pnpm"
  cat > "$P/staging/bin/pnpm" <<'PNPMEOF'
#!/system/bin/sh
# DSH 内置 pnpm 包装脚本（Android 适配）
#  · 不能用 #!/usr/bin/env node —— Android 没有 /usr/bin/env
#  · node 优先走 PATH（App 启动引擎时已把 payload/runtime/bin 放进 PATH），兜底按自身位置推导
#  · 默认 copy 导入：app 私有目录（SELinux）与 FUSE 外部存储都拒绝硬链接，pnpm 默认 hardlink 会失败
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v pnpm 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../pnpm/bin/pnpm.cjs" ]; then
  echo "dsh: 内置 pnpm 缺失（$DIR/../pnpm/bin/pnpm.cjs）" >&2
  exit 127
fi
exec "$NODE" "$DIR/../pnpm/bin/pnpm.cjs" --config.package-import-method=copy "$@"
PNPMEOF
  chmod +x "$P/staging/bin/pnpm"
  echo "  内置 pnpm: bin/pnpm + pnpm/ ($(du -sh "$P/staging/pnpm" | cut -f1))"
else
  echo "  !! 未找到 pnpm/（插件管理「添加插件」将不可用）"
fi

# 内置 git（v1.15.5）：插件管理的「添加插件」支持 GitHub 地址（git 形态的 spec 会先 git ls-remote 探活），
# AI 自己也能用 git。payload 原本不带 git → 与 pnpm 同因（子进程找不到命令）。
#   payload/bin/git                  ← 主程序（在引擎 PATH 上；git 还要靠 PATH 里的 git 拉起远程助手）
#   payload/git/libexec/git-core/    ← git-remote-https / git-remote-http（独立二进制，非内建）
#   payload/git/templates/           ← 仓库模板（配 GIT_TEMPLATE_DIR）
# 取舍：不打包 libexec/git-core 里那 146 个与主程序同尺寸的硬链接副本（git-add/git-branch/… 多为内建，
#      由主程序在进程内处理）；不带 less（分页器，用 GIT_PAGER=cat 代替）。
#      依赖库 libiconv.so / libcharset.so 走 runtime/lib（已在 LD_LIBRARY_PATH 上）。
if [ -d "$H/git" ]; then
  rm -rf "$P/staging/git"
  mkdir -p "$P/staging/git/libexec/git-core"
  cp -L "$H/git/bin/git" "$P/staging/bin/git"
  cp -L "$H/git/libexec/git-core/git-remote-https" "$P/staging/git/libexec/git-core/"
  cp -L "$H/git/libexec/git-core/git-remote-http"  "$P/staging/git/libexec/git-core/"
  cp -r "$H/git/templates" "$P/staging/git/templates"
  chmod +x "$P/staging/bin/git" "$P/staging/git/libexec/git-core/"*
  echo "  内置 git: bin/git + git/ ($(du -sh "$P/staging/git" | cut -f1))"
else
  echo "  !! 未找到 git/（「添加插件」的 GitHub 地址输入与 AI 的 git 将不可用）"
fi

# 内置 Python（v1.16.0）：AI 常要跑脚本做数据处理/解析，payload 原本完全没有 python。
# 走项目既有老路（rg / curl / git / pnpm 都是这么来的）：Termux aarch64 deb 解包。
#   payload/bin/python3  ← wrapper（payload/bin 在引擎 PATH 上，AI 直接 python3 就能用）
#   payload/python/      ← 本体（bin/python3.14 + lib/python3.14 标准库 + lib-dynload）
# 依赖库统一走 runtime/lib（已在 LD_LIBRARY_PATH 上），本版新增 13 个 .so 及其 soname 实体名：
#   libpython3.14 / libandroid-support / libandroid-posix-semaphore / libbz2 / libcrypt /
#   libexpat / libgdbm(+compat) / liblzma / libncursesw / libpanelw / libreadline / libzstd
#   （libffi / libsqlite3 / libssl / libcrypto / libz / libc++_shared 早已随 node 带入，复用）
# 取舍：不带 include/（C 头）与 pkgconfig —— 只有现场编译 C 扩展才需要；
#      PYTHONHOME **不用设**：python 按 argv0 自推 prefix=<payload>/python（真机实测通过）。
if [ -d "$H/python" ]; then
  rm -rf "$P/staging/python"
  cp -r "$H/python" "$P/staging/python"
  cat > "$P/staging/bin/python3" <<'PYEOF'
#!/system/bin/sh
# DSH 内置 Python 包装脚本
#  · 不能用 #!/usr/bin/env python3 —— Android 没有 /usr/bin/env
#  · LD_LIBRARY_PATH 兜底：正常由 App 启动引擎时设好，这里防手工执行时缺库
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v python3 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
[ -n "$LD_LIBRARY_PATH" ] || { LD_LIBRARY_PATH="$DIR/../runtime/lib"; export LD_LIBRARY_PATH; }
if [ -z "$DIR" ] || [ ! -x "$DIR/../python/bin/python3.14" ]; then
  echo "dsh: 内置 python 缺失（$DIR/../python/bin/python3.14）" >&2
  exit 127
fi
exec "$DIR/../python/bin/python3.14" "$@"
PYEOF
  chmod +x "$P/staging/bin/python3"
  cp "$P/staging/bin/python3" "$P/staging/bin/python"
  chmod +x "$P/staging/bin/python"
  cat > "$P/staging/bin/pip" <<'PIPEOF'
#!/system/bin/sh
# DSH 内置 pip 包装脚本（与 bin/python3 同源，只换 -m 参数）
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v pip 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
[ -n "$LD_LIBRARY_PATH" ] || { LD_LIBRARY_PATH="$DIR/../runtime/lib"; export LD_LIBRARY_PATH; }
if [ -z "$DIR" ] || [ ! -x "$DIR/../python/bin/python3.14" ]; then
  echo "dsh: 内置 python 缺失（$DIR/../python/bin/python3.14）" >&2
  exit 127
fi
exec "$DIR/../python/bin/python3.14" -m pip "$@"
PIPEOF
  chmod +x "$P/staging/bin/pip"
  cp "$P/staging/bin/pip" "$P/staging/bin/pip3"
  chmod +x "$P/staging/bin/pip3"
  echo "  内置 python: bin/python3 + python/ ($(du -sh "$P/staging/python" | cut -f1))"
else
  echo "  !! 未找到 python/（AI 的 python / python3 将不可用）"
fi

# 内置 npm（v1.16.0）：AI 需要自己装 node 包。payload **已有 node v26.4.0**（引擎就跑在它上面，
# 且 runtime/bin 在引擎 PATH 上，AI 本就能直接 node xxx.js），但 Termux 的 nodejs 包不含 npm
# （npm 是单列的另一个 deb），所以「装 node 包」这条链是断的。
#   payload/bin/npm  ← wrapper → node + payload/npm/bin/npm-cli.js
#   payload/bin/npx  ← wrapper → node + payload/npm/bin/npx-cli.js
#   payload/npm/     ← npm 本体（bin/ lib/ node_modules/ package.json …）
# 取舍：裁掉 docs/（2.4M）与 man/（466K）—— 纯文档，运行时用不到，16M → 13M。
#      全局安装落点由 HOME 决定（App 里 HOME = filesDir，可写，无需额外配置）。
if [ -d "$H/npm" ]; then
  rm -rf "$P/staging/npm"
  cp -r "$H/npm" "$P/staging/npm"
  cat > "$P/staging/bin/npm" <<'NPMEOF'
#!/system/bin/sh
# DSH 内置 npm 包装脚本
#  · 不能用 #!/usr/bin/env node —— Android 没有 /usr/bin/env
#  · node 优先走 PATH（App 启动引擎时已把 payload/runtime/bin 放进 PATH），兜底按自身位置推导
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v npm 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../npm/bin/npm-cli.js" ]; then
  echo "dsh: 内置 npm 缺失（$DIR/../npm/bin/npm-cli.js）" >&2
  exit 127
fi
#  · 全局安装落点改到 $HOME/.npm-global：默认前缀是 <payload>/runtime（payload 内，
#    升级会被覆盖，且未必可写）；HOME = App filesDir，可写且跨版本升级保留。
[ -n "$HOME" ] && { NPM_CONFIG_PREFIX="$HOME/.npm-global"; export NPM_CONFIG_PREFIX; }
exec "$NODE" "$DIR/../npm/bin/npm-cli.js" "$@"
NPMEOF
  chmod +x "$P/staging/bin/npm"
  cat > "$P/staging/bin/npx" <<'NPXEOF'
#!/system/bin/sh
# DSH 内置 npx 包装脚本（同 npm，只换入口）
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v npx 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../npm/bin/npx-cli.js" ]; then
  echo "dsh: 内置 npx 缺失（$DIR/../npm/bin/npx-cli.js）" >&2
  exit 127
fi
exec "$NODE" "$DIR/../npm/bin/npx-cli.js" "$@"
NPXEOF
  chmod +x "$P/staging/bin/npx"
  echo "  内置 npm: bin/npm + bin/npx + npm/ ($(du -sh "$P/staging/npm" | cut -f1))"
else
  echo "  !! 未找到 npm/（AI 的 npm / npx 将不可用）"
fi

# Shizuku 运行时（rish dex）：独立放 assets 供权限界面检测，同时放 payload 供 DSH 插件调用
cp "$H/rish/rish_shizuku.dex" "$P/assets/rish_shizuku.dex"
mkdir -p "$P/staging/rish"
cp "$H/rish/rish_shizuku.dex" "$P/staging/rish/rish_shizuku.dex"
chmod 644 "$P/staging/rish/rish_shizuku.dex"

# v1.9 虚拟屏服务 jar（VirtualScreenServer）：独立于 App classes，供 app_process 特权加载。
# app_process -Djava.class.path 必须指向含 *.dex 的 jar（裸 dex 会报 Unsupported class loader）。
# 单独 javac 编译 src/.../vscreen/ 源码 → d8 成 classes.dex → jar 打包 → 放 assets（App 提取后传给引擎）
echo "== vscreen jar =="
VSC_SRC="$P/src/com/deepseek/harness/vscreen"
mkdir -p "$P/out/vsc-classes" "$P/out/vsc-dex"
if ls "$VSC_SRC"/*.java >/dev/null 2>&1; then
  if "$JAVA/javac" -source 1.8 -target 1.8 -bootclasspath "$AJ" -d "$P/out/vsc-classes" "$VSC_SRC"/*.java >"$P/out/vsc-javac.log" 2>&1; then
    ( cd "$P/out/vsc-classes" && "$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/vsc-dex" $(find . -name '*.class' | sed 's|^\./||') ) 2>/tmp/vsc-d8.log || ( echo "  d8 retry"; cd "$P/out/vsc-classes" && find . -name '*.class' > "$P/out/vsc.rsp" && "$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/vsc-dex" @"$P/out/vsc.rsp" 2>/tmp/vsc-d8b.log )
    if [ -f "$P/out/vsc-dex/classes.dex" ]; then
      # 用 jar 把 classes.dex 打成 jar（app_process 认含 dex 的 jar）
      ( cd "$P/out/vsc-dex" && "$JAVA/jar" cf "$P/assets/vscreen_shizuku.jar" classes.dex )
      echo "  vscreen_shizuku.jar: $(stat -c%s "$P/assets/vscreen_shizuku.jar") bytes"
    else
      echo "  !! vscreen dex 生成失败（d8）"
    fi
  else
    echo "  !! vscreen javac 失败，日志：$P/out/vsc-javac.log"
    tail -10 "$P/out/vsc-javac.log"
  fi
else
  echo "  (无 vscreen 源码，跳过)"
fi

# 移动端适配资源：随 APK 打包，MainActivity 注入 WebView（不依赖服务器 dist）
cp "$P/../mobile-patch/mobile.css" "$P/assets/mobile.css"
cp "$P/../mobile-patch/mobile.js" "$P/assets/mobile.js"

cp "$H/.dsh/cordis.patch.yml" "$P/staging/dshhome/"
cp "$H/.dsh/profiles/web/cordis.patch.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/cordis.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/package.json" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/pnpm-workspace.yaml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/settings.yaml" "$P/staging/dshhome/" 2>/dev/null   || cp "$H/.dsh/settings.yaml.imported" "$P/staging/dshhome/settings.yaml"   || { echo "!! 找不到 .dsh/settings.yaml（也没有 settings.yaml.imported），中止"; exit 1; }

# 安全检查：payload 里绝不能出现 API Key 或凭证文件
if grep -rqE "sk-[A-Za-z0-9]{20,}" "$P/staging" 2>/dev/null; then
  echo "!! 检测到 API Key 混入 payload，中止"; exit 1
fi
if [ -e "$P/staging/dshhome/.credentials.yaml" ]; then
  echo "!! 检测到 .credentials.yaml，中止"; exit 1
fi

echo "--- payload 各部分大小 ---"
du -sh "$P/staging/runtime" "$P/staging/dshroot" "$P/staging/dshhome" "$P/staging/bin"

( cd "$P/staging" && jar cMf "$P/assets/payload.zip" . )
echo "payload.zip: $(du -sh "$P/assets/payload.zip" | cut -f1)"

echo "== 1/7 资源编译 (aapt) =="
mkdir -p "$P/out/gen" "$P/out/classes" "$P/out/dex"
aapt package -f -m -J "$P/out/gen" -M "$P/AndroidManifest.xml" -S "$P/res" -I "$AJ"

echo "== 2/7 javac =="
# 解压 Shizuku API + provider + aidl 的 classes.jar 供编译和 dex
SHIZUKU_CLS="$P/out/shizuku-cls"
rm -rf "$SHIZUKU_CLS"; mkdir -p "$SHIZUKU_CLS"
for AAR in "$P/libs/shizuku-api.aar" "$P/libs/shizuku-provider.aar" "$P/libs/shizuku-aidl.aar"; do
  TMP="$P/out/$(basename "$AAR" .aar)"
  rm -rf "$TMP"; mkdir -p "$TMP"
  ( cd "$TMP" && "$JAVA/jar" xf "$AAR" classes.jar )
  ( cd "$SHIZUKU_CLS" && "$JAVA/jar" xf "$TMP/classes.jar" )
done
SHIZUKU_JARS="$P/out/shizuku-api/classes.jar${CP_SEP}$P/out/shizuku-provider/classes.jar${CP_SEP}$P/out/shizuku-aidl/classes.jar"
# Windows(Git Bash)：MSYS 不转换"分号分隔的 POSIX 路径列表"，javac 会整体当一条路径找不到 → 用 cygpath 转 Windows 路径
GEN_CP="$P/out/gen"
if [ "$CP_SEP" = ";" ] && command -v cygpath >/dev/null 2>&1; then
  GEN_CP="$(cygpath -w "$P/out/gen")"
  SHIZUKU_JARS="$(cygpath -w "$P/out/shizuku-api/classes.jar")${CP_SEP}$(cygpath -w "$P/out/shizuku-provider/classes.jar")${CP_SEP}$(cygpath -w "$P/out/shizuku-aidl/classes.jar")"
fi
# javac 必须成功：失败立即中止（曾因 javac 找不到而产出无 MainActivity 的坏 APK，安装即闪退）
# v48：vscreen 全量源码编入 APK（App 进程内嵌 Operit server，不再单独 jar 部署）
VSC_SRC="$P/src/com/deepseek/harness/vscreen"
if ! "$JAVA/javac" -source 1.8 -target 1.8 -bootclasspath "$AJ" \
  -classpath "$GEN_CP${CP_SEP}$SHIZUKU_JARS" -d "$P/out/classes" \
  "$P/src/com/deepseek/harness/MainActivity.java" "$P/src/com/deepseek/harness/EngineService.java" "$P/src/com/deepseek/harness/AlarmReceiver.java" "$P/src/com/deepseek/harness/ScheduleExecutor.java" "$P/src/com/deepseek/harness/OverlayService.java" "$P/src/com/deepseek/harness/UsageStatsHelper.java" "$P/src/com/deepseek/harness/AccessibilityService.java" "$P/src/com/deepseek/harness/VsreenBridgeService.java" "$P/src/com/deepseek/harness/LogShareProvider.java" \
  "$VSC_SRC"/*.java \
  "$P/out/gen/com/deepseek/harness/R.java" \
  >"$P/out/javac.log" 2>&1; then
  echo "!! javac 编译失败，日志：$P/out/javac.log"
  tail -20 "$P/out/javac.log"
  exit 1
fi
grep -v "bootstrap class path\|warning:\|RestrictTo\|Note:\|deprecat" "$P/out/javac.log" || true
NCLASS="$(find "$P/out/classes" -name '*.class' | wc -l)"
echo "  javac 完成，class 数：$NCLASS"
[ "$NCLASS" -gt 0 ] || { echo "!! javac 产物为空，中止构建"; exit 1; }

echo "== 3/7 d8 -> dex =="
# class 列表写入 response file（Windows 命令行 8191 字符限制，98+ 个绝对路径会超长）
CLS_RSP="$P/out/classes.rsp"
if [ "$CP_SEP" = ";" ] && command -v cygpath >/dev/null 2>&1; then
  { find "$P/out/classes" -name '*.class'; find "$SHIZUKU_CLS" -name '*.class'; } | cygpath -w -f - > "$CLS_RSP"
else
  { find "$P/out/classes" -name '*.class'; find "$SHIZUKU_CLS" -name '*.class'; } > "$CLS_RSP"
fi
"$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/dex" @"$CLS_RSP" \
  || { echo "!! d8 失败"; exit 1; }
# 校验 dex 必须包含 MainActivity（防止再次产出安装即闪退的坏包）
if ! grep -aq "MainActivity" "$P/out/dex/classes.dex"; then
  echo "!! classes.dex 缺少 MainActivity，中止构建"; exit 1
fi
echo "  classes.dex 含 MainActivity，$(stat -c%s "$P/out/dex/classes.dex") bytes"

echo "== 4/7 aapt 打包 + assets =="
aapt package -f -M "$P/AndroidManifest.xml" -S "$P/res" -I "$AJ" -A "$P/assets" -0 zip -F "$P/out/unsigned.apk"
( cd "$P/out/dex" && aapt add "$P/out/unsigned.apk" classes.dex )

echo "== 5/7 zipalign =="
zipalign -f 4 "$P/out/unsigned.apk" "$P/out/aligned.apk"

echo "== 6/7 签名 =="
[ -f "$KEY" ] || { echo "!! 缺少签名密钥 $KEY（release.jks 不入仓库，请自行准备）"; exit 1; }
"$APKSIGNER" sign --ks "$KEY" --ks-pass "pass:${KEYSTORE_PASS:?请先 export KEYSTORE_PASS=签名密码}" --ks-key-alias "${KEYSTORE_ALIAS:-dsh}" --key-pass "pass:$KEYSTORE_PASS" \
  --out "$P/DeepSeekHarness.apk" "$P/out/aligned.apk"

echo "== 7/7 校验 =="
"$APKSIGNER" verify --print-certs "$P/DeepSeekHarness.apk"
aapt dump badging "$P/DeepSeekHarness.apk" | head -8
ls -la "$P/DeepSeekHarness.apk"
echo "BUILD OK -> $P/DeepSeekHarness.apk"
