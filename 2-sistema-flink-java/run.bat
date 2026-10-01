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

rem --- 1. localizar un JDK 17 o superior ---
rem     No se usa el java del PATH: en este equipo apunta a un JRE 8 roto.
rem     Orden: JAVA_HOME, luego el JDK que dejo instalar.bat en
rem     .herramientas\jdk, y por ultimo los JDK del sistema.
set "MVN_JAVA="
if defined JAVA_HOME (
    if exist "!JAVA_HOME!\bin\javac.exe" set "MVN_JAVA=!JAVA_HOME!"
)
if not defined MVN_JAVA (
    for /d %%D in ("%~dp0..\.herramientas\jdk\*") do (
        if not defined MVN_JAVA (
            if exist "%%~fD\bin\javac.exe" set "MVN_JAVA=%%~fD"
        )
    )
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
    echo.
    echo [ERROR] No se encontro un JDK 17 o superior.
    echo.
    echo        El pom.xml pide Java 17, asi que un JDK 11 no alcanza
    echo        para compilar este proyecto. Se recomienda un JDK 21 LTS.
    echo.
    echo        Descargalo desde:
    echo          https://adoptium.net/temurin/releases/?version=21
    echo        Elige "Windows" y "x64", el paquete .zip o .msi.
    echo        Al instalarlo en su ruta por defecto, este script lo
    echo        encuentra solo y no hay que hacer nada mas.
    echo.
    echo        Si ya lo instalaste en otra carpeta, define JAVA_HOME:
    echo          set JAVA_HOME=C:\ruta\del\jdk
    echo.
    exit /b 1
)
set "JAVA_BIN=!MVN_JAVA!\bin\java.exe"

rem --- 2. compilar y dejar los jars en target\lib ---
rem     La compilacion va en dos modos. Con los jars ya descargados se usa
rem     -o (offline) y es rapido. Sin ellos hay que dejar que Maven los
rem     baje, porque en una maquina nueva ~/.m2 esta vacio y con -o
rem     fallaria al no poder descargarse nada.
set "JARS_ESPERADOS=45"
set "JARS_PRESENTES=0"
if exist "target\lib" (
    for /f %%C in ('dir /b "target\lib\*.jar" 2^>nul ^| find /c /v ""') do set "JARS_PRESENTES=%%C"
)

if !JARS_PRESENTES! lss !JARS_ESPERADOS! (
    echo [1/2] Compilando con Maven...
    echo       Primera compilacion: se descargan las dependencias de Flink.
    echo       Esto se hace una sola vez; despues ya no se necesita internet.
    echo       Usa .herramientas\jdk\ si no hay JDK en el sistema,
    echo       o borra target\lib\ si esa carpeta esta incompleta.
    echo.
    call mvnw.cmd -q compile
) else (
    echo [1/2] Compilando con Maven...
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
    echo        Eso fuerza a descargarla de nuevo.
    echo.
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
    if not defined MONDO_SIN_PAUSE pause
    exit /b 1
)

echo.
echo [OK] El job termino. Los resultados estan en Mundo.resumen_regiones
echo.
rem  El pause se salta cuando otro script, como arrancar.bat, llama a este
rem  archivo: si no, ese script se quedaria esperando una tecla.
if not defined MONDO_SIN_PAUSE pause
exit /b 0
