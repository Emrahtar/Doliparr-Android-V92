@echo off
REM SAG TIKLAYIP "Yonetici olarak calistir" deyin. Telefondan 8765 portuna erisime izin verir.
netsh advfirewall firewall delete rule name="DoliScan Role" >nul 2>&1
netsh advfirewall firewall add rule name="DoliScan Role" dir=in action=allow protocol=TCP localport=8765 profile=any
echo.
echo Tamam. Bu pencereyi kapatabilirsiniz.
pause
