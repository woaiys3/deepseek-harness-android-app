#!/system/bin/sh
# DSH 修复工具 APK 构建（独立工具，与主 App 同签名）
set -e
BE=/data/user/0/com.deepseek.harness/files/buildenv/usr
P=/sdcard/github/android-app-fix
AJ=/data/user/0/com.deepseek.harness/files/buildenv/build/platform33/android-13/android.jar
JAVA=$BE/lib/jvm/java-17-openjdk/bin
export LD_LIBRARY_PATH=$BE/lib
export PATH=$JAVA/bin:$BE/bin:$PATH
KEY=/sdcard/github/android-app/release.jks
# 签名密码不再硬编码（本文件历史上明文写过密码，该密码应视为已泄露）：从环境变量取，缺失时明确报错
KS_PASS="${KEYSTORE_PASS:?请先 export KEYSTORE_PASS=签名密码}"

cd $P
rm -rf out gen classes.dex
mkdir -p out/gen out/classes out/dex

echo "== 1/5 aapt 资源编译 =="
aapt package -f -m -J out/gen -M AndroidManifest.xml -S res -I $AJ

echo "== 2/5 javac =="
$JAVA/javac -source 1.8 -target 1.8 -bootclasspath $AJ -classpath out/gen -d out/classes \
  src/com/deepseek/harness/fix/MainActivity.java out/gen/com/deepseek/harness/fix/R.java 2>&1 | grep -v "warning:" | head -5
echo "  class 数: $(find out/classes -name '*.class' | wc -l)"

echo "== 3/5 d8 =="
d8 --release --lib $AJ --min-api 24 --output out/dex $(find out/classes -name '*.class')

echo "== 4/5 打包 + assets =="
aapt package -f -M AndroidManifest.xml -S res -I $AJ -A assets -F out/unsigned.apk
( cd out/dex && aapt add $P/out/unsigned.apk classes.dex )

echo "== 5/5 zipalign + 签名 =="
zipalign -f 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks $KEY --ks-pass "pass:$KS_PASS" --ks-key-alias dsh --key-pass "pass:$KS_PASS" \
  --out $P/DSH修复工具.apk out/aligned.apk
apksigner verify --print-certs $P/DSH修复工具.apk | grep -E "SHA-256|DN" | head -2
ls -la $P/DSH修复工具.apk
echo "BUILD OK"
