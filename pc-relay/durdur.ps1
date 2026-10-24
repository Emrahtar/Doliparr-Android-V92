# Çalışan röleyi durdurur. -Kaldir verilirse otomatik başlatmayı da kaldırır.
param([switch]$Kaldir)
Get-CimInstance Win32_Process |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like '*relay.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
if ($Kaldir) {
    $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) "DoliScan Role.lnk"
    if (Test-Path $lnk) { Remove-Item $lnk }
    Write-Host "Röle durduruldu ve otomatik başlatma kaldırıldı."
} else {
    Write-Host "Röle durduruldu."
}
