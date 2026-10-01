@echo off
rem ============================================================================
rem  compilar.bat - solo compila, no ejecuta.
rem
rem  Deja las clases en target\classes y los 45 jars de las dependencias
rem  en target\lib. Despues el navegador web ya puede leer de MongoDB.
rem
rem  Igual que run.bat: con los jars ya descargados compila en modo offline,
rem  que es rapido. Sin ellos los deja bajar de Maven Central, porque en una
rem  maquina nueva ~/.m2 esta vacio y el modo offline fallaria.
rem ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo [1/1] Compilando con Maven...
echo.

set "JARS_ESPERADOS=45"
set "JARS_PRESENTES=0"
if exist "target\lib" (
    for /f %%C in ('dir /b "target\lib\*.jar" 2^>nul ^| find /c /v ""') do set "JARS_PRESENTES=%%C"
)

if !JARS_PRESENTES! lss !JARS_ESPERADOS! (
    echo       Primera compilacion: se descargan las dependencias de Flink.
    echo       Esto se hace una sola vez; despues ya no se necesita internet.
    echo.
    call mvnw.cmd -q compile
) else (
    echo       Modo offline: las dependencias ya estan descargadas.
    echo.
    call mvnw.cmd -o -q compile
)

if errorlevel 1 (
    echo.
    echo [ERROR] La compilacion fallo.
    echo.
    echo        Si dice que no encuentra una dependencia, la carpeta
    echo        target\lib esta incompleta. Borrala y vuelve a correr:
    echo          rmdir /s /q target\lib
    echo.
    exit /b 1
)

echo [OK] Compilacion correcta.
echo      Clases   : target\classes
echo      Librerias: target\lib
echo.
exit /b 0
