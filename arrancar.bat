@echo off
rem ============================================================================
rem  arrancar.bat - corre el job de Apache Flink y levanta la interfaz web.
rem
rem  Correrlo TODAS las veces que quieras: no instala nada.
rem
rem    arrancar.bat              el job y luego la web
rem    arrancar.bat solo-web     solo la web, sin Java ni Flink
rem    arrancar.bat solo-job     solo el job, sin levantar la web
rem ============================================================================

setlocal
cd /d "%~dp0"

echo.
echo   Solo hay dos pasos: instalar.bat, y despues arrancar.bat.
echo   La base de datos se carga a mano, no se toca desde aqui.
echo.

rem --- PowerShell es lo que hace el trabajo real -----------------------------
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
    echo.
    pause
    exit /b 1
)

rem  Los argumentos se pasan tal cual para llegar a arrancar.ps1, que es el
rem  que entiende solo-web y solo-job.
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0arrancar.ps1" %*
set "CODIGO=%ERRORLEVEL%"

echo.
if not "%CODIGO%"=="0" (
    echo   Algo no salio bien. Los mensajes de arriba dicen que.
    echo.
)
if not defined MONDO_SIN_PAUSE pause
exit /b %CODIGO%