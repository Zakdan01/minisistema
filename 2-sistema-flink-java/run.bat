@echo off
rem ============================================================================
rem  run.bat - ejecuta el job por lotes de Apache Flink
rem
rem  No usa exec-maven-plugin a proposito: compila con Maven y despues
rem  arranca java directamente, para que los flags --add-opens se vean
rem  aqui y se puedan explicar. Flink necesita esos flags porque usa
rem  reflexion, y en Java 17+ los modulos estan cerrados por defecto.
rem
rem  El classpath es  target\classes;target\lib\*  :
rem  el primer termino son las clases del proyecto y el segundo el asterisco
rem  significa "todos los jars de esa carpeta", que Maven deja en target\lib.
rem ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ========================================================
echo   EJECUTANDO JOB POR LOTES - APACHE FLINK
echo ========================================================
echo.

rem --- 1. localizar un JDK 11 o superior ---
rem     No se usa el java del PATH: en este equipo apunta a un JRE 8 roto.
set "MVN_JAVA="
if defined JAVA_HOME (
    if exist "!JAVA_HOME!\bin\javac.exe" set "MVN_JAVA=!JAVA_HOME!"
)
if not defined MVN_JAVA (
    for /d %%D in ("%ProgramFiles%\Java\jdk-*") do (
        if not defined MVN_JAVA (
            if exist "%%~fD\bin\javac.exe" set "MVN_JAVA=%%~fD"
        )
    )
)
if not defined MVN_JAVA (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do (
        if not defined MVN_JAVA (
            if exist "%%~fD\bin\javac.exe" set "MVN_JAVA=%%~fD"
        )
    )
)
if not defined MVN_JAVA (
    echo [ERROR] No se encontro un JDK 11 o superior.
    exit /b 1
)
set "JAVA_BIN=!MVN_JAVA!\bin\java.exe"

rem --- 2. compilar y dejar los jars en target\lib ---
echo [1/2] Compilando con Maven...
call mvnw.cmd -o -q compile
if errorlevel 1 (
    echo [ERROR] La compilacion fallo.
    exit /b 1
)

rem --- 3. la consola necesita UTF-8 para mostrar los acentos ---
chcp 65001 >nul
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"

echo [2/2] Ejecutando el job...
echo.

rem --- 4. ejecutar con los flags que Flink necesita ---
"!JAVA_BIN!" ^
  --add-exports=java.base/sun.net.util=ALL-UNNAMED ^
  --add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED ^
  --add-opens=java.base/java.lang=ALL-UNNAMED ^
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED ^
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^
  --add-opens=java.base/java.net=ALL-UNNAMED ^
  --add-opens=java.base/java.nio=ALL-UNNAMED ^
  --add-opens=java.base/java.util=ALL-UNNAMED ^
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED ^
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED ^
  --add-opens=java.base/sun.security.action=ALL-UNNAMED ^
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED ^
  -cp "target\classes;target\lib\*" ^
  com.minisistema.AnalisisFlinkBatch

if errorlevel 1 (
    echo.
    echo [ERROR] El job fallo.
    echo.
    pause
    exit /b 1
)

echo.
echo [OK] El job termino. Los resultados estan en Mundo.resumen_regiones
echo.
pause
exit /b 0
