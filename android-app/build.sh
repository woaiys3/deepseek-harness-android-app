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
         "$P/staging/bin" "$P/staging/dshroot" \
         "$P/staging/dshhome/profiles/web" "$P/assets"

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
( cd "$H/dshroot/lib" && tar cf - --exclude='./node_modules/@deepseek-ai/dsh/node_modules/.bin' . ) \
  | ( cd "$P/staging/dshroot/lib" && tar xf - )

# dshroot 版本标记：App 用它判断「外部 /sdcard/DeepSeekHarness/dshroot」是否需要补齐。
# 外部已有的文件永不覆盖（保留 AI 运行时修改），缺失文件才从 APK 补上。
DSHROOT_REV="$(date +%Y%m%d%H%M%S)"
echo "$DSHROOT_REV" > "$P/staging/dshroot/REVISION"
echo "$DSHROOT_REV" > "$P/assets/dshroot_revision.txt"

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

# Shizuku 运行时（rish dex）：独立放 assets 供权限界面检测，同时放 payload 供 DSH 插件调用
cp "$H/rish/rish_shizuku.dex" "$P/assets/rish_shizuku.dex"
mkdir -p "$P/staging/rish"
cp "$H/rish/rish_shizuku.dex" "$P/staging/rish/rish_shizuku.dex"
chmod 644 "$P/staging/rish/rish_shizuku.dex"

# 移动端适配资源：随 APK 打包，MainActivity 注入 WebView（不依赖服务器 dist）
cp "$P/../mobile-patch/mobile.css" "$P/assets/mobile.css"
cp "$P/../mobile-patch/mobile.js" "$P/assets/mobile.js"

cp "$H/.dsh/cordis.patch.yml" "$P/staging/dshhome/"
cp "$H/.dsh/profiles/web/cordis.patch.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/cordis.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/package.json" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/pnpm-workspace.yaml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/settings.yaml" "$P/staging/dshhome/"

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
if ! "$JAVA/javac" -source 1.8 -target 1.8 -bootclasspath "$AJ" \
  -classpath "$GEN_CP${CP_SEP}$SHIZUKU_JARS" -d "$P/out/classes" \
  "$P/src/com/deepseek/harness/MainActivity.java" "$P/src/com/deepseek/harness/EngineService.java" "$P/src/com/deepseek/harness/AlarmReceiver.java" "$P/src/com/deepseek/harness/ScheduleExecutor.java" "$P/src/com/deepseek/harness/OverlayService.java" "$P/src/com/deepseek/harness/UsageStatsHelper.java" "$P/out/gen/com/deepseek/harness/R.java" \
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
