# Compila o EX2 Live 3D nativo (Kotlin + Filament) e assina com a chave de plataforma da central.
# O aapt2 do Android não aceita acentos no caminho ("Projeto Padrão"): compila numa cópia ASCII.
# Saída: dist\EX2-Live3D-<versão>.apk
param([switch]$Clean, [switch]$Emu)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$proj = Split-Path $root -Parent
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$bt = Join-Path $sdk 'build-tools\36.0.0'
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
$gradle = Join-Path $env:USERPROFILE '.ex2tools\gradle-9.8.2\bin\gradle.bat'
$env:JAVA_HOME = $jbr
$env:ANDROID_HOME = $sdk
# o Java no Windows cria sockets locais na pasta temporária; caminho longo/estranho → "Unable to
# establish loopback connection". Pasta curta, sem espaços, para o Gradle e o daemon.
$short = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($env:USERPROFILE).ShortPath
$jtmp = Join-Path $short '.ex2tools\t'
New-Item -ItemType Directory -Force $jtmp | Out-Null
$env:TEMP = $jtmp; $env:TMP = $jtmp
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$jtmp -Djdk.net.unixdomain.tmpdir=$jtmp"
$work = Join-Path ([IO.Path]::GetTempPath()) 'ex2native-build'
if ($work -match '[^\x00-\x7F]') { $work = 'C:\ex2native-build' }
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist, $work | Out-Null

function Step($name) { Write-Host "== $name" -ForegroundColor Cyan }

Step 'copiar projeto para pasta ASCII'
# robocopy espelha só o que mudou (o cache do Gradle em $work\.gradle e build\ continua valendo)
& robocopy $root $work /MIR /XD dist tools assets-src build .gradle /XF build.ps1 /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy falhou ($LASTEXITCODE)" }
"sdk.dir=$($sdk -replace '\\','\\')" | Set-Content -Encoding ascii (Join-Path $work 'local.properties')
Add-Content -Encoding ascii (Join-Path $work 'gradle.properties') "`norg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$($jtmp -replace '\\','/') -Djdk.net.unixdomain.tmpdir=$($jtmp -replace '\\','/')"

Step 'gradle assembleRelease'
Push-Location $work
try {
    $tasks = @('assembleRelease', '--console=plain', '--warning-mode=summary')
    if ($Clean) { $tasks = @('clean') + $tasks }
    if ($Emu) { $tasks += '-Pemu' }
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "gradle falhou ($LASTEXITCODE)" }
} finally {
    # o PC tem pouca memória livre: o Gradle parado em segundo plano segurava ~1,2 GB e o emulador
    # não conseguia subir (travava ou sumia). Desliga o Gradle ao terminar.
    & $gradle --stop --console=plain | Out-Null
    Pop-Location
}

Step 'assinar (chave de plataforma AOSP = chave da IHU629G)'
$unsigned = Get-ChildItem (Join-Path $work 'app\build\outputs\apk\release') -Filter *.apk | Select-Object -First 1
# chaves de plataforma (platform.pk8 + platform.x509.pem): variável EX2_KEYS, ou ex2-native\keys, ou ex2-wallpaper\keys
$keys = if ($env:EX2_KEYS) { $env:EX2_KEYS } elseif (Test-Path (Join-Path $root 'keys\platform.pk8')) { Join-Path $root 'keys' } else { Join-Path $proj 'ex2-wallpaper\keys' }
if (-not (Test-Path (Join-Path $keys 'platform.pk8'))) { throw "faltam as chaves de plataforma em $keys (veja o README)" }
$tmpKeys = Join-Path $work 'keys'
New-Item -ItemType Directory -Force $tmpKeys | Out-Null
Copy-Item "$keys\platform.pk8", "$keys\platform.x509.pem" $tmpKeys -Force
$aligned = Join-Path $work 'aligned.apk'
& "$bt\zipalign.exe" -p -f 4 $unsigned.FullName $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign falhou' }
$signed = Join-Path $work 'signed.apk'
# (o Java novo escreve um aviso "restricted method" no canal de erro: não é falha; vale o código de saída)
$ErrorActionPreference = 'Continue'
& "$bt\apksigner.bat" sign --key "$tmpKeys\platform.pk8" --cert "$tmpKeys\platform.x509.pem" --out $signed $aligned 2>$null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'apksigner falhou' }

$gradleFile = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$version = [regex]::Match($gradleFile, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$apk = Join-Path $dist ("EX2-Live3D-$version" + $(if ($Emu) { '-emu' } else { '' }) + '.apk')
Copy-Item -Force $signed $apk
Write-Host ("APK pronto: {0} ({1} MB)" -f $apk, [math]::Round((Get-Item $apk).Length / 1MB, 1)) -ForegroundColor Green
