# download_cores.ps1
# Script to automate downloading and setting up sing-box.exe and wintun.dll

$singboxVersion = "1.13.12"
$wintunVersion = "0.14.1"

$assetsDir = "D:\workspace\Spider\assets\binaries\windows"
if (!(Test-Path $assetsDir)) {
    New-Item -ItemType Directory -Force -Path $assetsDir
}

# Download sing-box
$singboxUrl = "https://github.com/SagerNet/sing-box/releases/download/v$singboxVersion/sing-box-$singboxVersion-windows-amd64.zip"
$singboxZip = "D:\workspace\Spider\sing-box-temp.zip"
$singboxExtracted = "D:\workspace\Spider\sing-box-temp"

Write-Host "Downloading sing-box v$singboxVersion..."
Invoke-WebRequest -Uri $singboxUrl -OutFile $singboxZip

Write-Host "Extracting sing-box..."
Expand-Archive -Path $singboxZip -DestinationPath $singboxExtracted -Force

$exePath = Get-ChildItem -Path $singboxExtracted -Filter "sing-box.exe" -Recurse | Select-Object -First 1
if ($exePath) {
    Copy-Item -Path $exePath.FullName -Destination "$assetsDir\sing-box.exe" -Force
    Write-Host "Successfully placed sing-box.exe in assets."
} else {
    Write-Error "Failed to find sing-box.exe in extracted archive."
}

# Download wintun
$wintunUrl = "https://www.wintun.net/builds/wintun-$wintunVersion.zip"
$wintunZip = "D:\workspace\Spider\wintun-temp.zip"
$wintunExtracted = "D:\workspace\Spider\wintun-temp"

Write-Host "Downloading wintun v$wintunVersion..."
Invoke-WebRequest -Uri $wintunUrl -OutFile $wintunZip

Write-Host "Extracting wintun..."
Expand-Archive -Path $wintunZip -DestinationPath $wintunExtracted -Force

$dllPath = "$wintunExtracted\wintun\bin\amd64\wintun.dll"
if (Test-Path $dllPath) {
    Copy-Item -Path $dllPath -Destination "$assetsDir\wintun.dll" -Force
    Write-Host "Successfully placed wintun.dll in assets."
} else {
    Write-Error "Failed to find wintun.dll in extracted archive."
}

# Clean up
Write-Host "Cleaning up temporary files..."
Remove-Item -Path $singboxZip -Force -ErrorAction SilentlyContinue
Remove-Item -Path $singboxExtracted -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -Path $wintunZip -Force -ErrorAction SilentlyContinue
Remove-Item -Path $wintunExtracted -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Done!"
