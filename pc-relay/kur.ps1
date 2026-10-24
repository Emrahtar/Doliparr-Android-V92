# Röleyi Windows açılışına ekler (pencere açmadan, sistem tepsisinde) ve hemen başlatır.
$vbs = Join-Path $PSScriptRoot "relay-gizli-baslat.vbs"
$lnk = Join-Path ([Environment]::GetFolderPath('Startup')) "DoliScan Role.lnk"
$s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
$s.TargetPath = "wscript.exe"
$s.Arguments = "`"$vbs`""
$s.WorkingDirectory = $PSScriptRoot
$s.Save()
Start-Process wscript.exe -ArgumentList "`"$vbs`""
Write-Host "Tamam. Röle Windows açılınca otomatik başlayacak; sağ alttaki sistem tepsisinde (^ okunun içinde) mavi 'D' simgesi olarak görünür."
