# Emulador de teste do EX2 Live 3D (imita a central: 1920x968, densidade 160).
#   .\emu.ps1                  abre o emulador (se já não estiver aberto) e espera ficar pronto
#   .\emu.ps1 -Install         ... e instala o APK de emulador mais novo de dist\ e abre o app
#   .\emu.ps1 -Stop            fecha o emulador direito (salva o estado: a próxima abertura é rápida)
# Por que existe: o PC tem pouca memória livre; abrir do zero toda vez, esperar sem limite e fechar
# à força deixavam o emulador travado ou sumindo.
param([switch]$Install, [switch]$Stop, [int]$TimeoutSec = 240, [string]$Avd = '')
# 'Continue': o adb escreve avisos normais no canal de erro ("daemon not running; starting now");
# com 'Stop' o PowerShell 5.1 tratava isso como falha. Erros de verdade usam throw abaixo.
$ErrorActionPreference = 'Continue'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emu = Join-Path $sdk 'emulator\emulator.exe'
# emulador da central: Android 9 sem Play Store, 1920x1080 deitado, 2 GB (o antigo, de celular com Play Store,
# exigia 4 GB e o processo passava de 6 GB). Para usar o antigo: .\emu.ps1 -Avd Medium_Phone_API_37.0
if (-not $Avd) { $Avd = 'EX2_Central_A9' }
$avd = $Avd

function Booted { try { ((& $adb shell getprop sys.boot_completed 2>$null) -join '').Trim() -eq '1' } catch { $false } }

if ($Stop) {
    if (Booted) { & $adb emu kill | Out-Null; Write-Host 'emulador fechado (estado salvo)' }
    else { Get-Process qemu-system*, emulator -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; Write-Host 'emulador não respondia: encerrado' }
    exit 0
}

cmd /c "`"$adb`" start-server >nul 2>&1"
# ligado de verdade = sistema iniciado E o gerenciador de pacotes responde (já aconteceu de o
# sistema "cair" com o emulador aberto: aparecia ligado, mas nada instalava)
function Ready { (Booted) -and (((& $adb shell pm path android 2>$null) -join '') -match 'package:') }
if (-not (Ready)) {
    # processo pendurado (sem resposta ao adb): encerra antes de abrir de novo
    $hung = Get-Process qemu-system*, emulator -ErrorAction SilentlyContinue
    if ($hung) { Write-Host 'emulador travado encontrado: encerrando'; $hung | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep 3 }
    $os = Get-CimInstance Win32_OperatingSystem
    $freeGb = [math]::Round($os.FreePhysicalMemory / 1MB, 1)
    Write-Host "memória livre: $freeGb GB"
    if ($freeGb -lt 2.5) { Write-Host 'AVISO: pouca memória livre; feche programas pesados se o emulador demorar' -ForegroundColor Yellow }
    # sempre do zero (sem carregar nem salvar estado): o estado salvo depois de fechamentos à força
    # voltava "pronto" em 9 s mas travado (nem comando simples respondia)
    # memória: sem '-memory' o emulador dava 4 GB ao Android (ignorando os 2 GB do AVD) e o processo passava de 6 GB;
    # o vídeo integrado usa a mesma RAM do PC, então sobrava ~0,5 GB e o 3D chegava a 'quebrar' (malhas amassadas)
    Start-Process $emu -ArgumentList '-avd', $avd, '-no-boot-anim', '-no-snapshot-load', '-no-snapshot-save', '-no-audio', '-camera-back', 'none', '-camera-front', 'none', '-gpu', 'host' -WindowStyle Minimized
    $t0 = Get-Date
    while (-not (Booted)) {
        if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSec) { throw "emulador não ficou pronto em $TimeoutSec s" }
        Start-Sleep 3
    }
    # pronto de verdade = o gerenciador de pacotes responde (senão a instalação fica presa)
    while (((& $adb shell pm path android 2>$null) -join '') -notmatch 'package:') {
        if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSec) { throw 'emulador ligou mas não responde' }
        Start-Sleep 3
    }
    Write-Host ("emulador pronto em {0:N0} s" -f ((Get-Date) - $t0).TotalSeconds)
}
& $adb shell wm size 1920x968 | Out-Null
& $adb shell wm density 160 | Out-Null

if ($Install) {
    $apk = Get-ChildItem (Join-Path $PSScriptRoot 'dist') -Filter '*-emu.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Write-Host "instalando $($apk.Name)"
    & $adb shell am force-stop br.com.ex2.live3d | Out-Null
    & $adb install -r --abi x86_64 $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'instalação falhou' }
    & $adb shell am start -n br.com.ex2.live3d/.MainActivity | Out-Null
    Write-Host 'app aberto'
}
