$ErrorActionPreference = "Stop"

$JDK_BIN = "C:\Users\ASUS\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\TwoPlayerGamesBuild\jdk-17.0.20.1+1\bin"
$SDK_DIR = "C:\Users\ASUS\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\Android\Sdk"
$BUILD_TOOLS = "$SDK_DIR\build-tools\34.0.0"
$ANDROID_JAR = "$SDK_DIR\platforms\android-35\android.jar"

$ROOT = "d:\AI_App\messenger-unsend-android"
$APP_MAIN = "$ROOT\android-app\app\src\main"
$JAVA_SRC = "$ROOT\android-app\java-src\com\msgguard\app"
$BUILD_DIR = "$ROOT\build-out"
$OUT_APK = "$ROOT\public\MsgGuard-AntiUnsend.apk"
$ROOT_APK = "$ROOT\MsgGuard-AntiUnsend.apk"

if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\dex" | Out-Null
New-Item -ItemType Directory -Force -Path "$APP_MAIN\res\drawable" | Out-Null
New-Item -ItemType Directory -Force -Path "$APP_MAIN\res\values" | Out-Null

# Tao icon PNG 144x144 chuan cho App Android
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap(144, 144)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$brushBg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point(0, 0)),
    (New-Object System.Drawing.Point(144, 144)),
    [System.Drawing.Color]::FromArgb(255, 37, 99, 235),
    [System.Drawing.Color]::FromArgb(255, 124, 58, 237)
)
$g.FillRectangle($brushBg, 0, 0, 144, 144)
$font = New-Object System.Drawing.Font("Arial", 46, [System.Drawing.FontStyle]::Bold)
$brushWhite = [System.Drawing.Brushes]::White
$g.DrawString("MG", $font, $brushWhite, 16, 36)
$g.Dispose()
$bmp.Save("$APP_MAIN\res\drawable\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Host "[1/7] Bien dich tai nguyen res/ (icon + strings.xml) bang aapt2 compile..."
& "$BUILD_TOOLS\aapt2.exe" compile --dir "$APP_MAIN\res" -o "$BUILD_DIR\compiled_res.zip"

Write-Host "[2/7] Dong goi AndroidManifest.xml, res va assets bang aapt2 link..."
& "$BUILD_TOOLS\aapt2.exe" link `
    -I $ANDROID_JAR `
    --auto-add-overlay `
    -R "$BUILD_DIR\compiled_res.zip" `
    --manifest "$APP_MAIN\AndroidManifest.xml" `
    -A "$APP_MAIN\assets" `
    --min-sdk-version 26 `
    --target-sdk-version 33 `
    --version-code 8 `
    --version-name "4.0.0-ULTIMATE" `
    --java "$BUILD_DIR\gen" `
    -o "$BUILD_DIR\unaligned.apk"

Write-Host "[3/7] Bien dich ma nguon Java sang Bytecode (.class)..."
$javaFiles = @(Get-ChildItem "$JAVA_SRC\*.java" | Select-Object -ExpandProperty FullName)
$rFiles = @(Get-ChildItem "$BUILD_DIR\gen" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName)
$allSources = $javaFiles + $rFiles

& "$JDK_BIN\javac.exe" `
    -source 1.8 -target 1.8 `
    -encoding UTF-8 `
    -bootclasspath $ANDROID_JAR `
    -cp $ANDROID_JAR `
    -d "$BUILD_DIR\classes" `
    $allSources

Write-Host "[4/7] Chuyen doi Bytecode sang Dalvik Executable (classes.dex) bang D8..."
$classFiles = @(Get-ChildItem "$BUILD_DIR\classes" -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName)
& "$JDK_BIN\java.exe" -cp "$BUILD_TOOLS\lib\d8.jar" com.android.tools.r8.D8 `
    --lib $ANDROID_JAR `
    --min-api 24 `
    --output "$BUILD_DIR\dex" `
    $classFiles

Write-Host "[5/7] Them classes.dex vao APK bang aapt.exe chuan Android..."
Push-Location "$BUILD_DIR\dex"
& "$BUILD_TOOLS\aapt.exe" add "$BUILD_DIR\unaligned.apk" "classes.dex"
Pop-Location

Write-Host "[6/7] Can chinh bo nho 4-byte bang zipalign..."
& "$BUILD_TOOLS\zipalign.exe" -f -p 4 "$BUILD_DIR\unaligned.apk" "$BUILD_DIR\aligned.apk"
& "$BUILD_TOOLS\zipalign.exe" -c -v 4 "$BUILD_DIR\aligned.apk"

Write-Host "[7/7] Ky so chung chi Android (Giu nguyen Keystore de Cap Nhat khong mat quyen)..."
$KEYSTORE = "$ROOT\msgguard-master.keystore"
if (-not (Test-Path $KEYSTORE)) {
    & "$JDK_BIN\keytool.exe" -genkeypair `
        -keystore $KEYSTORE `
        -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -startdate "2022/01/01 00:00:00" `
        -storepass android -keypass android `
        -dname "CN=Android Debug, O=Android, C=US"
}

& "$JDK_BIN\java.exe" -jar "$BUILD_TOOLS\lib\apksigner.jar" sign `
    --ks $KEYSTORE `
    --ks-key-alias androiddebugkey `
    --ks-pass pass:android `
    --key-pass pass:android `
    --min-sdk-version 21 `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $OUT_APK `
    "$BUILD_DIR\aligned.apk"

Copy-Item "$APP_MAIN\assets\index.html" "$ROOT\public\index.html" -Force
Copy-Item "$APP_MAIN\assets\app.js" "$ROOT\public\app.js" -Force
Copy-Item "$APP_MAIN\assets\style.css" "$ROOT\public\style.css" -Force

Copy-Item $OUT_APK $ROOT_APK -Force
Copy-Item $OUT_APK "$ROOT\public\MsgGuard-v2.apk" -Force
Copy-Item $OUT_APK "$ROOT\MsgGuard-v2.apk" -Force
Copy-Item $OUT_APK "$ROOT\public\MsgGuard-Offline.apk" -Force
Copy-Item $OUT_APK "$ROOT\MsgGuard-Offline.apk" -Force
Copy-Item $OUT_APK "$ROOT\public\MsgGuard-Pro.apk" -Force
Copy-Item $OUT_APK "$ROOT\MsgGuard-Pro.apk" -Force
Copy-Item $OUT_APK "$ROOT\public\MsgGuard-v3.apk" -Force
Copy-Item $OUT_APK "$ROOT\MsgGuard-v3.apk" -Force
& "$BUILD_TOOLS\zipalign.exe" -c -v 4 $OUT_APK
& "$JDK_BIN\java.exe" -jar "$BUILD_TOOLS\lib\apksigner.jar" verify --verbose --print-certs $OUT_APK
Write-Host "HOAN TAT! File APK da san sang tai: $OUT_APK"
