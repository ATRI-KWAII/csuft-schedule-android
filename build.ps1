$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkspaceRoot = Split-Path -Parent $ProjectRoot
$BuildDrive = 'R:'
if (-not (Test-Path -LiteralPath "$BuildDrive\schedule-apk")) {
    & subst $BuildDrive $WorkspaceRoot
    if ($LASTEXITCODE -ne 0) { throw '无法创建临时构建盘符 R:' }
}
$WorkspaceBuildRoot = "$BuildDrive\"
$ProjectRoot = Join-Path $WorkspaceBuildRoot 'schedule-apk'
$SdkRoot = Join-Path $WorkspaceBuildRoot 'work\android-sdk'
$BuildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
$AndroidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$BuildDir = Join-Path $ProjectRoot 'build'
$GenDir = Join-Path $BuildDir 'gen'
$ClassDir = Join-Path $BuildDir 'classes'
$DexDir = Join-Path $BuildDir 'dex'
$OutputDir = Join-Path $WorkspaceRoot 'outputs'

New-Item -ItemType Directory -Force -Path $BuildDir,$GenDir,$ClassDir,$DexDir,$OutputDir | Out-Null

& (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $ProjectRoot 'res') -o (Join-Path $BuildDir 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
& (Join-Path $BuildTools 'aapt2.exe') link -o (Join-Path $BuildDir 'base.apk') -I $AndroidJar --manifest (Join-Path $ProjectRoot 'AndroidManifest.xml') --java $GenDir --min-sdk-version 23 --target-sdk-version 35 (Join-Path $BuildDir 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

$Sources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$Sources += @(Get-ChildItem -LiteralPath $GenDir -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -classpath $AndroidJar -d $ClassDir $Sources
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }
$ClassesJar = Join-Path $BuildDir 'classes.jar'
& jar cf $ClassesJar -C $ClassDir .
if ($LASTEXITCODE -ne 0) { throw 'classes.jar 创建失败' }
& (Join-Path $BuildTools 'd8.bat') --lib $AndroidJar --min-api 23 --output $DexDir $ClassesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Copy-Item -LiteralPath (Join-Path $BuildDir 'base.apk') -Destination (Join-Path $BuildDir 'unsigned.apk') -Force
& jar uf (Join-Path $BuildDir 'unsigned.apk') -C $DexDir classes.dex
& (Join-Path $BuildTools 'zipalign.exe') -f 4 (Join-Path $BuildDir 'unsigned.apk') (Join-Path $BuildDir 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

$Keystore = Join-Path $BuildDir 'schedule-debug.keystore'
if (-not (Test-Path -LiteralPath $Keystore)) {
    & keytool -genkeypair -keystore $Keystore -storepass android -keypass android -alias schedule -dname 'CN=Local Development,O=Codex,C=CN' -keyalg RSA -keysize 2048 -validity 3650
}
$Apk = Join-Path $OutputDir 'schedule-lite-1.7.apk'
$SignedApk = Join-Path $BuildDir 'signed.apk'
& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias schedule --ks-pass pass:android --key-pass pass:android --out $SignedApk (Join-Path $BuildDir 'aligned.apk')
& (Join-Path $BuildTools 'apksigner.bat') verify --verbose $SignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }
Copy-Item -LiteralPath $SignedApk -Destination $Apk -Force
Write-Output $Apk
