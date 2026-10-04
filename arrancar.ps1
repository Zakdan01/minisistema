<#
    arrancar.ps1 - corre el job de Flink y levanta la interfaz web.

    Se ejecuta desde arrancar.bat, que es el que se abre con doble clic.
    Los dos pasos se pueden pedir por separado:

      arrancar.bat              corre el job y luego la web
      arrancar.bat solo-web     solo la web, sin tocar Flink ni Java
      arrancar.bat solo-job     solo el job, sin levantar la web

    Igual que instalar.ps1, no falla entero si una parte no esta: la base de
    datos se da por hecha y, si no esta, solo se avisa. El job se corre igual
    y la web arranca siempre, que es mas util que un error en negro.

    Aqui NO se carga nada en la base de datos. La preparacion de la base es un
    paso manual de cada persona y este script no la toca.
#>

$ErrorActionPreference = 'Continue'

$raiz      = $PSScriptRoot
$flinkDir  = Join-Path $raiz '2-sistema-flink-java'
$webDir    = Join-Path $raiz '3-interfaz-web-python'
$venvPy    = Join-Path $webDir '.venv\Scripts\python.exe'
$servidor  = Join-Path $webDir 'servidor.py'

$PUERTO = 8000

$modo = 'todo'
if ($args.Count -gt 0) {
    switch ($args[0].ToLower()) {
        'solo-web' { $modo = 'web' }
        'solo-job' { $modo = 'job' }
        default    { $modo = 'todo' }
    }
}

function Escribir-Titulo {
    Write-Host ''
    Write-Host ('=' * 64) -ForegroundColor DarkCyan
    Write-Host "  $args" -ForegroundColor Cyan
    Write-Host ('=' * 64) -ForegroundColor DarkCyan
}
function Escribir-Ok   { Write-Host "  [OK] $args" -ForegroundColor Green }
function Escribir-Falta{ Write-Host "  [!] $args" -ForegroundColor Yellow }
function Escribir-Mal  { Write-Host "  [X] $args" -ForegroundColor Red }

# ---------------------------------------------------------------------------
#  Comprobacion de la base de datos
# ---------------------------------------------------------------------------
#  Esto es lo UNICO que se hace con la base de datos: mirarla. No escribe
#  nada, no crea colecciones y no carga paises. Cargar los paises es un paso
#  manual de cada persona y este script no lo hace ni lo intenta.
#
#  Se reutiliza consultas.estado() del propio proyecto, que ya comprueba lo
#  mismo en solo lectura y devuelve mongo, paises, regiones y jobCorrido.
#  Es mejor que escribir otra comprobacion aparte: es la misma que usa la
#  pagina para decidir si tiene que avisar de algo.

function Comprobar-Base {
    # Sin el entorno virtual no hay pymongo, y sin pymongo no se puede
    # preguntar a la base. Se avisa del motivo en vez de fallar en silencio.
    if (-not (Test-Path $venvPy)) {
        return @{ motivo = 'sin-pymongo' }
    }

    # El codigo de Python va SIN comillas dobles porque PowerShell 5.1 se las
    # come al pasar argumentos a un programa nativo y python recibe una
    # expresion rota. Por eso se imprime con json.dumps.
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Push-Location $webDir
        try {
            $salida = & $venvPy -c 'import json,consultas;print(json.dumps(consultas.estado()))' 2>&1
        }
        finally {
            Pop-Location
        }
    }
    finally {
        $ErrorActionPreference = $anterior
    }

    # Se busca la linea que empieza por { porque python puede avisar algo
    # antes, del tipo "Picked up ...".
    $texto = ($salida | Out-String)
    $linea = ($texto -split "`r?`n" |
              Where-Object { $_.Trim().StartsWith('{') } |
              Select-Object -First 1)

    if (-not $linea) { return @{ motivo = 'sin-respuesta' } }

    try {
        $estado = $linea | ConvertFrom-Json
    }
    catch {
        return @{ motivo = 'sin-respuesta' }
    }

    return @{ motivo = 'ok'; estado = $estado }
}

function Probar-Puerto {
    param([int]$Puerto, [int]$EsperaMs = 1500)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $t = $c.ConnectAsync('127.0.0.1', $Puerto)
        $v = ($t.Wait($EsperaMs) -and $c.Connected)
        $c.Close()
        return $v
    }
    catch {
        return $false
    }
}

function Probar-PuertoMongo {
    return (Probar-Puerto -Puerto 27017 -EsperaMs 2000)
}

function Avisar-Base {
    $r = Comprobar-Base

    if ($r.motivo -ne 'ok') {
        # Plan B: al menos saber si algo escucha en el puerto.
        if (Probar-PuertoMongo) {
            Escribir-Falta 'MongoDB responde, pero no se pudo comprobar la base de datos.'
            if ($r.motivo -eq 'sin-pymongo') {
                Escribir-Ok 'Es que falta el entorno virtual: corre  instalar.bat'
            }
        }
        else {
            Escribir-Falta 'MongoDB no responde en localhost:27017.'
        }
        Escribir-Ok 'La base de datos la carga cada quien por su cuenta. Aqui no se'
        Escribir-Ok 'instala ni se modifica nada. El job y la web siguen igual.'
        return $false
    }

    $e = $r.estado

    if (-not $e.mongo) {
        Escribir-Falta "No se pudo conectar con MongoDB: $($e.error)"
        Escribir-Ok 'La base de datos la carga cada quien por su cuenta. Aqui no se'
        Escribir-Ok 'instala ni se modifica nada. El job y la web siguen igual.'
        return $false
    }

    if ($e.paises -eq 0) {
        Escribir-Falta "MongoDB $($e.servidor) responde, pero Mundo.paises esta vacia."
    }
    elseif (-not $e.jobCorrido) {
        Escribir-Ok "Base de datos lista: $($e.paises) paises (Mongo $($e.servidor))"
        Escribir-Falta 'El job de Flink todavia no ha corrido: Mundo.resumen_regiones esta vacia.'
    }
    else {
        Escribir-Ok "Base de datos lista: $($e.paises) paises, $($e.regiones) regiones (Mongo $($e.servidor))"
    }

    return $true
}

# ---------------------------------------------------------------------------
#  Comprobaciones previas
# ---------------------------------------------------------------------------
#  Se revisa antes de hacer nada, para no arrancar medio proyecto y que el
#  usuario descubra a mitad que faltaba una pieza.

$venvListo = $false

Escribir-Titulo 'COMPROBANDO QUE TODO ESTE INSTALADO'

if (Test-Path $venvPy) {
    Escribir-Ok 'Entorno virtual de Python encontrado'
    $venvListo = $true
}
else {
    Escribir-Mal 'No esta el entorno virtual 3-interfaz-web-python\.venv'
    Escribir-Ok 'Corre  instalar.bat  una vez en esta maquina.'
}

# Solo avisa. El resultado no condiciona nada: el job se corre igual y la web
# arranca igual, porque la base de datos se da por hecha.
Avisar-Base | Out-Null

# El job de Flink no depende de Python, asi que la falta de venv no lo frena.
if (-not $venvListo) {
    Write-Host ''
    Escribir-Mal 'No se puede arrancar la interfaz web sin instalar antes.'
    exit 1
}

# ---------------------------------------------------------------------------
#  1. Job de Apache Flink
# ---------------------------------------------------------------------------

if ($modo -eq 'job' -or $modo -eq 'todo') {
    Escribir-Titulo '1. JOB DE APACHE FLINK'

    # MongoDB NO se comprueba para decidir esto. La base se da por hecha: si
    # de verdad no esta, el job falla solo con su propio error, que explica
    # mucho mejor que saltarselo en silencio.
    if (-not (Test-Path (Join-Path $flinkDir 'run.bat'))) {
        Escribir-Mal 'No se encuentra 2-sistema-flink-java\run.bat'
    }
    else {
        $codigoJob = 1
        Escribir-Ok 'Corriendo el job. Tarda unos segundos...'
        Write-Host ''

        # MONDO_SIN_PAUSE evita que run.bat se quede esperando una tecla, que
        # dejaria este script colgado sin poder hacer nada mas.
        $env:MONDO_SIN_PAUSE = '1'

        Push-Location $flinkDir
        try {
            & (Join-Path $flinkDir 'run.bat')
            $codigoJob = $LASTEXITCODE
        }
        finally {
            Pop-Location
            Remove-Item Env:\MONDO_SIN_PAUSE -ErrorAction SilentlyContinue
        }

        Write-Host ''
        if ($codigoJob -eq 0) {
            Escribir-Ok 'Job terminado. Los resumenes por region ya estan en MongoDB.'
        } else {
            Escribir-Falta "El job devolvio codigo $codigoJob. Mira el mensaje de arriba."
            Escribir-Ok 'La web sigue igual: lo unico que falta son los resumenes.'
        }
    }
}

if ($modo -eq 'job') {
    Write-Host ''
    pause
    exit 0
}

# ---------------------------------------------------------------------------
#  Cerrar el servidor de una corrida anterior
# ---------------------------------------------------------------------------
#  Si la ventana anterior se cerro con la X en vez de con Ctrl+C, el python
#  sigue vivo ocupando el puerto 8000. Antes esto solo avisaba y salia, y el
#  usuario se quedaba sin web. Ahora se cierra solo y se sigue.
#
#  Solo se mata el proceso si de verdad es nuestro: se comprueba que su linea
#  de comandos incluya servidor.py. Un programa ajeno que este escuchando en
#  el 8000 no se toca nunca, porque no es cosa de este script.

#  Devuelve $true si el puerto quedo libre, y $false si lo tiene un programa
#  que no es nuestro. En ese ultimo caso el script no intenta levantar el
#  servidor, porque no podria abrir el puerto y solo daria un error raro.

function Detener-ServidorViejo {
    $pids = @()

    try {
        $pids = @(Get-NetTCPConnection -LocalPort $PUERTO -State Listen -ErrorAction Stop |
                  Select-Object -ExpandProperty OwningProcess)
    }
    catch {
        # Get-NetTCPConnection no esta en Windows viejo: se lee netstat.
        $lineas = & netstat.exe -ano -p TCP 2>$null
        foreach ($linea in $lineas) {
            if ($linea -match "^\s*TCP\s+\S+:$PUERTO\s+\S+\s+LISTENING\s+(\d+)\s*$") {
                $pids += [int]$Matches[1]
            }
        }
    }

    $pids = @($pids | Where-Object { $_ -and $_ -gt 0 } | Select-Object -Unique)
    if ($pids.Count -eq 0) {
        Escribir-Ok "Puerto $PUERTO libre."
        return $true
    }

    $hayAjeno = $false

    foreach ($procId in $pids) {
        $info = $null
        try {
            $info = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction Stop
        }
        catch {
            continue
        }
        if (-not $info) { continue }

        if ($info.CommandLine -notlike '*servidor.py*') {
            Escribir-Falta "El puerto $PUERTO lo ocupa otro programa: $($info.Name) (pid $procId)"
            Escribir-Ok 'No se toca, porque no es de este proyecto.'
            $hayAjeno = $true
            continue
        }

        Escribir-Ok "Cerrando el servidor web de una corrida anterior (pid $procId)."
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 800
    }

    if ($hayAjeno) {
        Escribir-Ok "Cierra ese programa y vuelve a correr  arrancar.bat"
        Escribir-Ok "O cambia el puerto en la linea PUERTO de este script."
        return $false
    }

    if (Probar-Puerto -Puerto $PUERTO) {
        Escribir-Falta "El puerto $PUERTO sigue ocupado y no se pudo cerrar."
        return $false
    }

    Escribir-Ok "Puerto $PUERTO libre."
    return $true
}

# ---------------------------------------------------------------------------
#  2. Interfaz web
# ---------------------------------------------------------------------------

Escribir-Titulo '2. INTERFAZ WEB'

# Si quedo un servidor de una corrida anterior se cierra solo, y si lo que
# ocupa el puerto es otro programa se avisa sin tocarlo.
$libre = Detener-ServidorViejo

if (-not $libre) {
    Write-Host ''
    Escribir-Mal "No se levanta la web porque el puerto $PUERTO lo tiene otro programa."
    exit 1
}

$url = "http://localhost:$PUERTO"

# El navegador se abre despues de que el servidor este escuchando. Si se
# abriera antes, el navegadorCachea un error de conexion y hay que recargar.
Escribir-Ok "Levantando el servidor en $url"
Escribir-Ok 'Para detenerlo: Ctrl+C en esta ventana'
Write-Host ''

$hilo = Start-Job -ScriptBlock {
    param($rutaPy, $rutaServidor, $rutaTrabajo)
    Set-Location $rutaTrabajo
    & $rutaPy $rutaServidor
} -ArgumentList $venvPy, $servidor, $webDir

# Se espera a que el puerto acepte conexiones en vez de dormir 3 segundos a
# ciegas. Con la espera fija pasaba algo malo: si el servidor tardaba mas de
# 3 segundos se anunciaba "listo" sin que lo estuviera, y si no podia arrancar
# el error de verdad aparecia mucho despues o no aparecia.
$esperado = 25
$arriba = $false

for ($i = 0; $i -lt $esperado; $i++) {
    Start-Sleep -Seconds 1
    if ($hilo.State -ne 'Running') { break }
    if (Probar-Puerto -Puerto $PUERTO -EsperaMs 500) { $arriba = $true; break }
}

if ($arriba) {
    # Receive-Job y NO $hilo.Receive(): Start-Job devuelve un PSRemotingJob,
    # que no tiene ningun metodo Receive. Con el metodo salia un MethodNotFound
    # que, al ser ErrorActionPreference Continue, se tragaba en silencio y la
    # salida de servidor.py nunca se llegaba a ver.
    Receive-Job -Job $hilo | ForEach-Object { Write-Host $_ }
    Escribir-Ok "Servidor listo en $url. Se abrio el navegador."
    Start-Process $url
}
else {
    Escribir-Mal 'El servidor web no se pudo levantar.'
    Escribir-Ok 'Lo que salio de servidor.py:'
    # Aqui Receive-Job es lo mas importante: es la unica vez que el usuario ve
    # el error real de Python. Con $hilo.Receive() ese mensaje se perdia, que es
    # justo lo que este branch existe para mostrar.
    Receive-Job -Job $hilo 2>&1 | ForEach-Object { Write-Host $_ }
    Write-Host ''
    Write-Host '  Si el error es que la direccion ya esta en uso, es que otro'
    Write-Host '  programa tiene el puerto. Cambia PUERTO en este script.'
    exit 1
}

Write-Host ''
Write-Host '  La web queda corriendo mientras esta ventana siga abierta.'
Write-Host ''

# El pipe a Wait-Job mantiene viva la consola hasta que el job termine, que
# en el caso del servidor web es hasta que se cierre con Ctrl+C. Receive-Job
# despues va mostrando lo que el servidor escribe en cada peticion.
try {
    $hilo | Wait-Job | Receive-Job
}
finally {
    Remove-Job $hilo -Force -ErrorAction SilentlyContinue
}

exit 0