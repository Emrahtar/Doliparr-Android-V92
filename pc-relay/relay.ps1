# DoliScan kasa röle programı (sistem tepsisinde çalışır, görev çubuğunda yer kaplamaz)
# Telefondan gelen barkodu, klavyeden yazılmış gibi aktif pencereye (TakePOS) yazar ve Enter basar.
# Gizli kelime bu klasördeki token.txt dosyasından okunur (telefondaki ile birebir aynı olmalı).

$Port = 8765
# İsterseniz sadece TakePOS penceresi açıkken yazmasını sağlayın, örn: $TitleFilter = "TakePOS"
$TitleFilter = ""

$Token = "gizli123"
$tokenFile = Join-Path $PSScriptRoot "token.txt"
if (Test-Path $tokenFile) { $Token = (Get-Content -Raw $tokenFile).Trim() }

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class DsWin {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
}
"@
function Get-ActiveTitle {
    $sb = New-Object System.Text.StringBuilder 256
    [void][DsWin]::GetWindowText([DsWin]::GetForegroundWindow(), $sb, 256)
    return $sb.ToString()
}

# Aynı anda tek röle çalışsın
$createdNew = $false
$mutex = New-Object System.Threading.Mutex($true, "DoliScanRoleMutex", [ref]$createdNew)
if (-not $createdNew) { exit }

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
try { $listener.Start() } catch { exit }

# Sistem tepsisi simgesi
$bmp = New-Object System.Drawing.Bitmap 32, 32
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(30, 136, 229))), 1, 1, 30, 30)
$font = New-Object System.Drawing.Font("Segoe UI", 18, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString("D", $font, [System.Drawing.Brushes]::White, 8, 4)
$g.Dispose()
$icon = New-Object System.Windows.Forms.NotifyIcon
$icon.Icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
$icon.Text = "DoliScan Röle (port $Port)"
$menu = New-Object System.Windows.Forms.ContextMenuStrip
$info = $menu.Items.Add("DoliScan Röle çalışıyor")
$info.Enabled = $false
$exitItem = $menu.Items.Add("Çıkış")
$icon.ContextMenuStrip = $menu
$icon.Visible = $true

function Handle-Client($client) {
    try {
        $client.ReceiveTimeout = 1500
        $stream = $client.GetStream()
        $buf = New-Object byte[] 4096
        $n = $stream.Read($buf, 0, $buf.Length)
        $line = ([Text.Encoding]::ASCII.GetString($buf, 0, $n) -split "`r`n")[0]
        $code = "403 Forbidden"; $body = "istek-gecersiz"
        $tok = $null; $bar = $null
        if ($line -match 'token=([A-Za-z0-9_-]*)') { $tok = $Matches[1] }
        if ($line -match 'code=([A-Za-z0-9_-]+)') { $bar = $Matches[1] }
        if ($line -match '^GET /scan\?') {
            if ($tok -cne $script:Token) {
                $body = "gizli-kelime-uyusmuyor"
            } elseif (-not $bar) {
                $body = "barkod-yok"
            } elseif ($script:TitleFilter -ne "" -and (Get-ActiveTitle) -notlike "*$($script:TitleFilter)*") {
                $code = "409 Conflict"; $body = "takepos-penceresi-acik-degil"
            } else {
                [System.Windows.Forms.SendKeys]::SendWait($bar + "{ENTER}")
                $script:icon.Text = "DoliScan Röle - son: $bar"
                $code = "200 OK"; $body = "OK"
            }
        }
        $resp = "HTTP/1.1 $code`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n$body"
        $bytes = [Text.Encoding]::ASCII.GetBytes($resp)
        $stream.Write($bytes, 0, $bytes.Length)
    } catch { }
    finally { $client.Close() }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 80
$timer.Add_Tick({
    try {
        while ($script:listener.Pending()) {
            $c = $script:listener.AcceptTcpClient()
            Handle-Client $c
        }
    } catch { }
})
$exitItem.Add_Click({
    $script:timer.Stop()
    $script:listener.Stop()
    $script:icon.Visible = $false
    $script:icon.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$timer.Start()
[System.Windows.Forms.Application]::Run()
