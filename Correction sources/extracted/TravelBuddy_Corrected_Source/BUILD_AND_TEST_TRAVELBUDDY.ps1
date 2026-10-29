$ErrorActionPreference = "Stop"

Write-Host "TravelBuddy build preflight..." -ForegroundColor Cyan

$required = @(
  ".\settings.gradle.kts",
  ".\gradlew.bat",
  ".\app\build.gradle.kts",
  ".\app\google-services.json"
)
foreach ($item in $required) {
  if (-not (Test-Path $item)) {
    throw "Missing required project file: $item"
  }
}

Write-Host "1/4 Clean" -ForegroundColor Cyan
.\gradlew.bat clean

Write-Host "2/4 Verify Google Services" -ForegroundColor Cyan
.\gradlew.bat :app:processDebugGoogleServices

Write-Host "3/4 Build debug APK" -ForegroundColor Cyan
.\gradlew.bat assembleDebug

$apk = ".\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
  throw "Build completed but APK was not found at $apk"
}
Write-Host "APK created: $apk" -ForegroundColor Green

if (Get-Command adb -ErrorAction SilentlyContinue) {
  Write-Host "4/4 ADB detected. Connected devices:" -ForegroundColor Cyan
  adb devices
  Write-Host "To install/update the APK run:" -ForegroundColor Yellow
  Write-Host "adb install -r $apk"
  Write-Host "For Firebase debug logs run:" -ForegroundColor Yellow
  Write-Host 'adb logcat -c; adb logcat | findstr /I "AuthDebug FirestoreDebug MODE_SWITCH PERMISSION_DENIED"'
} else {
  Write-Host "ADB not found in PATH. Install the APK from Android Studio or add platform-tools to PATH." -ForegroundColor Yellow
}
