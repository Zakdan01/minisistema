@echo off
rem ============================================================================
rem  compilar.bat - solo compila, no ejecuta.
rem
rem  Deja las clases en target\classes y los 45 jars de las dependencias
rem  en target\lib. Despues el navegador web ya puede leer de MongoDB.
rem ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo [1/1] Compilando con Maven...
echo.

call mvnw.cmd -o -q compile
if errorlevel 1 (
    echo.
    echo [ERROR] La compilacion fallo.
    echo.
    exit /b 1
)

echo [OK] Compilacion correcta.
echo      Clases   : target\classes
echo      Librerias: target\lib
echo.
exit /b 0
