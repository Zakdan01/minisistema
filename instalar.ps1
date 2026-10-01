<#
    instalar.ps1 - prepara la maquina para poder correr el proyecto.

    Se ejecuta desde instalar.bat, que es el que se abre con doble clic.
    Todo lo que descarga o crea va dentro del proyecto, nada en el sistema:

      .herramientas\jdk\                 JDK 21, solo si no hay uno en la PC
      3-interfaz-web-python\.venv\       entorno virtual de Python
      2-sistema-flink-java\target\lib\   jars de Flink, los baja Maven

    Se puede volver a ejecutar las veces que haga falta: cada paso comprueba
    si ya esta hecho y se salta.

    Este script no verifica ni prepara la base de datos. Cargar los paises en
    MongoDB es un paso manual de cada persona y aqui no se hace. Lo unico que
    se hace es descargar las herramientas y librerias que hacen falta.
#>

$ErrorActionPreference = 'Stop'
$raiz     = $PSScriptRoot
$herramientas = Join-Path $raiz '.herramientas'
$jdkDir   = Join-Path $herramientas 'jdk'
$webDir   = Join-Path $raiz '3-interfaz-web-python'
$venvDir  = Join-Path $webDir '.venv'
$venvPy   = Join-Path $venvDir 'Scripts\python.exe'

$JDK_MINIMO = 17
$PY_MINIMO  = [Version]'3.9'

function Escribir-Titulo {
    Write-Host ''
    Write-Host ('=' * 64) -ForegroundColor DarkCyan
    Write-Host "  $args" -ForegroundColor Cyan
    Write-Host ('=' * 64) -ForegroundColor DarkCyan
}

function Escribir-Paso {
    Write-Host ''
    Write-Host "  $args" -ForegroundColor Yellow
}

function Escribir-Ok {
    Write-Host "  [OK] $args" -ForegroundColor Green
}

function Escribir-Falta {
    Write-Host "  [!] $args" -ForegroundColor Yellow
}

function Escribir-Mal {
    Write-Host "  [X] $args" -ForegroundColor Red
}

# ---------------------------------------------------------------------------
#  1. JDK
# ---------------------------------------------------------------------------
#  Se busca en este orden y se detiene en el primero que sirva:
#    1. JAVA_HOME
#    2. lo que dejo una instalacion anterior en .herramientas\jdk
#    3. los JDK del sistema
#
#  El orden importa: el JDK descargado queda antes que el del sistema para
#  que todos usen la misma version y los --add-opens de Flink sirvan igual
#  en cualquier maquina. JAVA_HOME manda sobre todo porque es lo que el
#  usuario eligio a proposito.

function Buscar-JdkEnEquipo {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        return $env:JAVA_HOME
    }
    if (Test-Path $jdkDir) {
        $local = Get-ChildItem -Path $jdkDir -Directory -ErrorAction SilentlyContinue |
                 Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
                 Sort-Object Name -Descending |
                 Select-Object -First 1
        if ($local) { return $local.FullName }
    }
    $patrones = @(
        (Join-Path $env:ProgramFiles 'Java\jdk-*'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-*'),
        (Join-Path $env:ProgramFiles 'Microsoft\jdk-*'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Java\*')
    )
    foreach ($patron in $patrones) {
        $encontrado = Get-ChildItem -Path $patron -Directory -ErrorAction SilentlyContinue |
                      Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
                      Sort-Object Name -Descending |
                      Select-Object -First 1
        if ($encontrado) { return $encontrado.FullName }
    }
    return $null
}

function Get-VersionJava {
    param([string]$RutaJdk)
    if (-not $RutaJdk) { return 0 }
    $javac = Join-Path $RutaJdk 'bin\javac.exe'
    if (-not (Test-Path $javac)) { return 0 }

    # javac escribe la version en la salida de error, y con
    # ErrorActionPreference en Stop eso PowerShell lo contaria como fallo.
    # Se relaja la politica solo durante esta llamada.
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $salida = & $javac -version 2>&1
    }
    finally {
        $ErrorActionPreference = $anterior
    }

    $texto = ($salida | Out-String)
    if ($texto -match '(\d+)\.(\d+)') {
        return [int]$Matches[1]
    }
    return 0
}

function Instalar-Jdk {
    Escribir-Paso 'Paso 1 de 3 - Java (necesario para el job de Apache Flink)'

    $jdk = Buscar-JdkEnEquipo
    $version = Get-VersionJava -RutaJdk $jdk

    if ($jdk -and $version -ge $JDK_MINIMO) {
        Escribir-Ok "JDK $version en $jdk"
        return $jdk
    }

    if ($jdk) {
        Escribir-Falta "Hay un Java $version en $jdk, pero se necesita $JDK_MINIMO o superior."
    } else {
        Escribir-Falta "No hay ningun JDK instalado en esta maquina."
    }

    Escribir-Ok "Se va a descargar un JDK 21 LTS a .herramientas\jdk (unos 196 MB)."

    if (-not (Test-Path $herramientas)) {
        New-Item -ItemType Directory -Path $herramientas | Out-Null
    }
    if (-not (Test-Path $jdkDir)) {
        New-Item -ItemType Directory -Path $jdkDir | Out-Null
    }

    $zip = Join-Path $jdkDir 'jdk21.zip'

    try {
        Escribir-Paso '  Consultando la API de Adoptium para saber que version bajar...'
        $api = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        $info = (Invoke-RestMethod -Uri $api -TimeoutSec 60)[0]

        $link     = $info.binary.package.link
        $checksum = $info.binary.package.checksum
        $nombre   = $info.release_name

        Escribir-Ok "  Version elegida: $nombre"
        Escribir-Paso '  Descargando... esto puede tardar varios minutos.'

        $progress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $link -OutFile $zip -UseBasicParsing -TimeoutSec 1800
        $ProgressPreference = $progress
    }
    catch {
        Escribir-Mal "No se pudo descargar el JDK: $($_.Exception.Message)"
        Escribir-Ok 'La descarga sale de github.com. Sin internet, o con un'
        Escribir-Ok 'firewall que lo bloquee, esto no va a funcionar.'
        Escribir-Ok ''
        Escribir-Ok 'Instalalo a mano y despues vuelve a correr este script:'
        Escribir-Ok '  https://adoptium.net/temurin/releases/?version=21'
        Escribir-Ok 'Elige Windows x64 en formato .zip, descomprimelo en'
        Escribir-Ok '  .herramientas\jdk\'
        return $null
    }

    if (-not (Test-Path $zip)) {
        Escribir-Mal 'La descarga termino pero no llego el archivo.'
        return $null
    }

    Escribir-Paso '  Comprobando que el archivo este completo...'
    $real = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
    if ($real -ne $checksum.ToLower()) {
        Escribir-Mal 'El archivo downloaded esta dañado o incompleto.'
        Escribir-Mal "  esperado: $checksum"
        Escribir-Mal "  obtenido: $real"
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        Escribir-Ok 'Se borro el archivo. Vuelve a correr este script para reintentar.'
        return $null
    }
    Escribir-Ok '  Checksum correcto.'

    Escribir-Paso '  Descomprimiendo...'
    try {
        Expand-Archive -Path $zip -DestinationPath $jdkDir -Force
    }
    catch {
        Escribir-Mal "No se pudo descomprimir: $($_.Exception.Message)"
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        return $null
    }
    Remove-Item $zip -Force -ErrorAction SilentlyContinue

    # El ZIP de Temurin trae una carpeta con el nombre de la version y dentro
    # el JDK, asi que la ruta final no se sabe de antemano: se busca el
    # javac.exe en vez de suponer la estructura.
    $instalado = Get-ChildItem -Path $jdkDir -Recurse -Directory -ErrorAction SilentlyContinue |
                 Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
                 Select-Object -First 1

    if (-not $instalado) {
        Escribir-Mal 'Se descomprimio pero no aparece el bin\javac.exe.'
        return $null
    }

    $version = Get-VersionJava -RutaJdk $instalado.FullName
    Escribir-Ok "JDK $version instalado en $($instalado.FullName)"
    return $instalado.FullName
}

# ---------------------------------------------------------------------------
#  2. Python
# ---------------------------------------------------------------------------
#  A diferencia del JDK, Python no se descarga. Se podria, pero el paquete
#  "embeddable" de Python no trae el modulo venv, que es justo lo que hace
#  falta para aislar las dependencias. Descargar el instalador completo y
#  ejecutarlo en silencio es mucho mas invasivo. Se pide el Python instalado,
#  que ademas ya tiene el launcher "py" en casi todas las maquinas.

function Buscar-Python {
    $candidatos = @(
        @{ Comando = 'py';      Args = @('-3', '-c', 'import sys;print(sys.executable)') },
        @{ Comando = 'python';  Args = @('-c', 'import sys;print(sys.executable)') },
        @{ Comando = 'python3'; Args = @('-c', 'import sys;print(sys.executable)') }
    )
    foreach ($c in $candidatos) {
        $cmd = Get-Command $c.Comando -ErrorAction SilentlyContinue
        if (-not $cmd) { continue }
        try {
            $salida = & $c.Comando @($c.Args) 2>&1
            $ruta = ($salida | Out-String).Trim()
            if ($ruta -and (Test-Path $ruta)) { return $ruta }
        }
        catch {
            continue
        }
    }
    return $null
}

function Get-VersionPython {
    param([string]$Ejecutable)
    if (-not $Ejecutable) { return $null }

    # El codigo de Python va SIN comillas dobles a proposito. PowerShell 5.1
    # las elimina al pasar argumentos a un programa nativo, y python recibe
    # una expresion rota. Por eso se imprime el numero suelto y la version se
    # arma aqui, en PowerShell.
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $salida = & $Ejecutable -c 'import sys;v=sys.version_info;print(v.major,v.minor,v.micro)' 2>&1
    }
    finally {
        $ErrorActionPreference = $anterior
    }

    $texto = (($salida | Out-String).Trim()) -replace '\s+', '.'
    if ($texto -match '^(\d+\.\d+\.\d+)') { return [Version]$Matches[1] }
    return $null
}

function Preparar-Python {
    Escribir-Paso 'Paso 2 de 3 - Python (necesario para la interfaz web)'

    $python = Buscar-Python
    $version = Get-VersionPython -Ejecutable $python

    if (-not $python) {
        Escribir-Mal 'No hay Python instalado en esta maquina.'
        Escribir-Ok ''
        Escribir-Ok 'Descargalo desde:'
        Escribir-Ok '  https://www.python.org/downloads/'
        Escribir-Ok 'Marca "Add python.exe to PATH" durante la instalacion y'
        Escribir-Ok 'despues vuelve a correr este script.'
        return $null
    }

    if ($version -and $version -lt $PY_MINIMO) {
        Escribir-Mal "Hay un Python $version en $python, pero se necesita $($PY_MINIMO) o superior."
        Escribir-Ok 'Instala uno mas nuevo y vuelve a correr este script.'
        return $null
    }

    Escribir-Ok "Python $version en $python"

    # El modulo venv es lo que permite aislar pymongo. En algunos Windows mal
    # configurados viene sin el, y ahi hay que reinstalar Python marcando
    # "pip" en las opciones avanzadas.
    & $python -c 'import venv' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Escribir-Mal 'Este Python no trae el modulo venv, asi que no se puede aislar pymongo.'
        Escribir-Ok 'Reinstala Python marcando "pip" en las Opciones avanzadas.'
        return $null
    }

    return $python
}

# ---------------------------------------------------------------------------
#  3. Entorno virtual
# ---------------------------------------------------------------------------

function Crear-Venv {
    param([string]$Python)

    Escribir-Paso 'Paso 3 de 3 - Entorno virtual de la interfaz web'

    if (Test-Path $venvPy) {
        Escribir-Ok 'Ya existe 3-interfaz-web-python\.venv, se reutiliza.'
    }
    else {
        Escribir-Ok 'Creando 3-interfaz-web-python\.venv ...'
        & $Python -m venv $venvDir
        if (-not (Test-Path $venvPy)) {
            Escribir-Mal 'No se pudo crear el entorno virtual.'
            return $false
        }
    }

    Escribir-Paso 'Instalando las dependencias de 3-interfaz-web-python\requirements.txt ...'
    Escribir-Ok '  (pymongo, y el driver que usa para hablar con MongoDB)'
    Write-Host ''

    $progress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    & $venvPy -m pip install --upgrade pip --quiet
    & $venvPy -m pip install -r (Join-Path $webDir 'requirements.txt')
    $codigo = $LASTEXITCODE
    $ProgressPreference = $progress

    if ($codigo -ne 0) {
        Escribir-Mal "pip fallo con codigo $codigo"
        Escribir-Ok 'Revisa la conexion a internet y vuelve a correr este script.'
        return $false
    }

    Escribir-Ok 'Dependencias instaladas.'
    return $true
}

# ---------------------------------------------------------------------------
#  Programa
# ---------------------------------------------------------------------------
#  Sobre la base de datos: este script no la verifica, no la prepara y no la
#  toca. Cargar los paises es un paso manual de cada persona. Lo unico que
#  hace es dejar listas las herramientas y las librerias que hacen falta para
#  correr el sistema.

Escribir-Titulo 'INSTALADOR DE MONDO - PREPARANDO ESTA MAQUINA'
Write-Host '  Todo se guarda dentro del proyecto. No se instala nada en el sistema.'

$jdkInstalado = Instalar-Jdk
Write-Host ''

$pythonInstalado = Preparar-Python
Write-Host ''

$venvListo = $false
if ($pythonInstalado) {
    $venvListo = Crear-Venv -Python $pythonInstalado
}

# --- Resumen ---------------------------------------------------------------

Escribir-Titulo 'COMO QUEDO ESTA MAQUINA'

Write-Host ''
if ($jdkInstalado) {
    Escribir-Ok "Java        listo   JDK $(Get-VersionJava -RutaJdk $jdkInstalado)"
} else {
    Escribir-Mal 'Java        FALTA   hace falta un JDK 17 o superior para el job de Flink'
}

if ($venvListo -and $pythonInstalado) {
    Escribir-Ok "Python      listo   .venv con Python $(Get-VersionPython -Ejecutable $venvPy)"
} else {
    Escribir-Mal 'Python      FALTA   la interfaz web necesita Python con pymongo'
}

Write-Host ''
Write-Host '  Siguiente paso:' -ForegroundColor Cyan
Write-Host '    1. Corre la base de datos a mano. Cada persona lo hace por su cuenta.'
Write-Host '    2. Arranca el sistema:'
Write-Host '       arrancar.bat'
Write-Host ''
Write-Host '  Puedes correr instalar.bat las veces que quieras: no rompe nada.'
Write-Host ''

if ($jdkInstalado -and $venvListo) {
    exit 0
} else {
    exit 1
}