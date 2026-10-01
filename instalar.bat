@echo off
rem ============================================================================
rem  instalar.bat - prepara esta maquina para correr el proyecto.
rem
rem  Se ejecuta UNA VEZ en cada computadora nueva. Puede volver a correr
rem  cuantas veces quiera: cada paso comprueba si ya esta hecho.
rem
rem  Crea:
rem    .herramientas\jdk\                 JDK 21, si la PC no tiene uno
rem    3-interfaz-web-python\.venv\       Python aislado con pymongo
rem
rem  Este script NO verifica ni prepara la base de datos. Cargar los paises
rem  en MongoDB es un paso manual de cada persona.
rem ============================================================================

setlocal
cd /d "%~dp0"

echo.
echo   Este script va a preparar esta maquina. Necesitas internet la
echo   primera vez para descargar Java y las dependencias de Python.
echo.

rem --- PowerShell es lo que hace el trabajo real -----------------------------
rem    Se busca a mano porque "powershell" no siempre esta en el PATH.
set "PS="
for %%P in (
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\pwsh.exe"
) do (
    if not defined PS if exist %%P set "PS=%%~P"
)
if not defined PS (
    for %%P in (powershell.exe pwsh.exe) do (
        if not defined PS for /f "delims=" %%Q in ('where %%P 2^>nul') do set "PS=%%Q"
    )
)

if not defined PS (
    echo [ERROR] No se encontro PowerShell en este Windows.
    echo         Es parte de Windows y deberia estar. Prueba actualizar Windows.
    echo.
    pause
    exit /b 1
)

rem --- Ejecutar instalar.ps1 -------------------------------------------------
rem    -ExecutionPolicy Bypass evita que la politica de la maquina bloquee un
rem    script local sin firmar. -NoProfile evita que un perfil de PowerShell
rem    del usuario rompa el output.
rem    El codigo de salida se pasa tal cual para saber si quedo todo listo.
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar.ps1"
set "CODIGO=%ERRORLEVEL%"

echo.
if "%CODIGO%"=="0" (
    echo   Listo. Ya puedes correr  arrancar.bat
) else (
    echo   Falto algo. Lee los mensajes de arriba y vuelve a correr
    echo   instalar.bat cuando lo resuelvas.
)
echo.

if not defined MONDO_SIN_PAUSE pause
exit /b %CODIGO%