param (
    [string]$Version = "",
    [string]$Changelog = "Регулярное обновление приложения"
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$gradleFile = "app/build.gradle.kts"
$gradleContent = Get-Content $gradleFile -Raw

if (-not $Version) {
    if ($gradleContent -match 'versionName\s*=\s*"([^"]+)"') {
        $Version = $matches[1]
    } else {
        $Version = "1.0.0"
    }
}

if ($gradleContent -match 'versionCode\s*=\s*(\d+)') {
    $code = [int]$matches[1]
} else {
    $code = 1
}

Write-Host "Сборка VK Tracks v$Version (code $code)..." -ForegroundColor Cyan

& ".\gradlew.bat" assembleRelease --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Error "Ошибка при сборке APK!"
    exit 1
}

$apkPath = "app/build/outputs/apk/release/VK-Tracks-$Version.apk"
if (-not (Test-Path $apkPath)) {
    $found = Get-ChildItem "app/build/outputs/apk/release" -Filter "*.apk" | Select-Object -First 1
    if ($found) {
        $apkPath = $found.FullName
    } else {
        Write-Error "APK файл не найден по пути $apkPath!"
        exit 1
    }
}

Write-Host "Собран APK: $apkPath" -ForegroundColor Green

# Читаем параметры keystore из keystore.properties или переменных окружения
$ksProps = @{}
if (Test-Path "keystore.properties") {
    Get-Content "keystore.properties" | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.+?)\s*$') {
            $ksProps[$matches[1]] = $matches[2]
        }
    }
}
$storeFile = if ($ksProps["storeFile"]) { $ksProps["storeFile"] } elseif ($env:VKTRACKS_KEYSTORE_PATH) { $env:VKTRACKS_KEYSTORE_PATH } else { "app/release.jks" }
$storePass = if ($ksProps["storePassword"]) { $ksProps["storePassword"] } else { $env:VKTRACKS_KEYSTORE_PASS }
$keyAlias  = if ($ksProps["keyAlias"]) { $ksProps["keyAlias"] } elseif ($env:VKTRACKS_KEY_ALIAS) { $env:VKTRACKS_KEY_ALIAS } else { "vktracks" }
$keyPass   = if ($ksProps["keyPassword"]) { $ksProps["keyPassword"] } else { $env:VKTRACKS_KEY_PASS }

if (-not $storePass -or -not $keyPass) {
    Write-Error "Не заданы пароли keystore! Заполните keystore.properties (см. keystore.properties.example) или переменные VKTRACKS_KEYSTORE_PASS / VKTRACKS_KEY_PASS."
    exit 1
}

# Подписание APK схемами v1 (JAR) и v2/v3 для гарантированной установки на всех Android (MIUI, HyperOS, AOSP)
Write-Host "Подписание APK схемами v1 + v2/v3..." -ForegroundColor Cyan
& "$env:JAVA_HOME\bin\java.exe" -jar "D:\Projects\AndroidSDK\build-tools\34.0.0\lib\apksigner.jar" sign `
    --ks "$storeFile" `
    --ks-pass "pass:$storePass" `
    --key-pass "pass:$keyPass" `
    --ks-key-alias "$keyAlias" `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --min-sdk-version 21 `
    "$apkPath"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Ошибка при подписании APK через apksigner!"
    exit 1
}

$apkSha256 = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()

# Копируем свежий APK в папку docs/ как прямое зеркало автообновления на GitHub Pages
Write-Host "Размещение APK на зеркале GitHub Pages (docs/)..." -ForegroundColor Cyan
Remove-Item "docs/*.apk" -ErrorAction SilentlyContinue
$apkFileName = Split-Path $apkPath -Leaf
Copy-Item $apkPath "docs/$apkFileName"

# Обновляем docs/update.json для автообновления
$updateObj = [ordered]@{
    versionCode = $code
    versionName = $Version
    downloadUrl = "https://github.com/ntvampire/vk-tracks/releases/download/v$Version/$apkFileName"
    mirrorUrl   = "https://ntvampire.github.io/vk-tracks/$apkFileName"
    sha256      = $apkSha256
    changelog   = $Changelog
}
$updateJson = $updateObj | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Join-Path (Get-Location) "docs/update.json"), $updateJson + "`r`n", (New-Object System.Text.UTF8Encoding($false)))

# Git commit и push
git add .
$commitMsg = "Release v${Version}: $Changelog"
git commit -m $commitMsg
git push origin main

# Создание релиза на GitHub
Write-Host "Публикация релиза v$Version в GitHub..." -ForegroundColor Cyan
gh release create "v$Version" "$apkPath" --title "VK Tracks v$Version" --notes "$Changelog"

Write-Host "Релиз v$Version успешно опубликован!" -ForegroundColor Green
Write-Host "Ссылка на релиз: https://github.com/ntvampire/vk-tracks/releases/tag/v$Version" -ForegroundColor Yellow
Write-Host "Ссылка на автообновление: https://ntvampire.github.io/vk-tracks/update.json" -ForegroundColor Yellow
