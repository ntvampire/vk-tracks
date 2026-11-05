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

# Обновляем docs/update.json для автообновления
$updateJson = @"
{
  "versionCode": $code,
  "versionName": "$Version",
  "downloadUrl": "https://github.com/ntvampire/vk-tracks/releases/download/v$Version/$(Split-Path $apkPath -Leaf)",
  "changelog": "$Changelog"
}
"@
Set-Content -Path "docs/update.json" -Value $updateJson -Encoding UTF8

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