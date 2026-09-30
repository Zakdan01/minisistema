@echo off
rem ============================================================================
rem  mvnw.cmd - lanzador de Maven para este proyecto
rem
rem  Hace tres cosas:
rem    1. Busca un JDK 11 o superior (ignora el java del PATH, que aqui
rem       apunta a un JRE 8 roto).
rem    2. Busca un Maven ya descargado en la carpeta del usuario.
rem       Si no lo encuentra, lo descarga de Maven Central.
rem    3. Se lo pasa a Maven junto con el pom.xml de este proyecto.
rem
rem  Uso:  mvnw.cmd compile
rem        mvnw.cmd package
rem ============================================================================

setlocal enabledelayedexpansion

set "MVN_VERSION=3.9.9"

rem ---------------------------------------------------------------------------
rem  1. Buscar un JDK 11 o superior
rem ---------------------------------------------------------------------------
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
    echo.
    echo [mvnw] ERROR: no se encontro un JDK 11 o superior.
    echo        Instala un JDK o define la variable JAVA_HOME.
    echo.
    exit /b 1
)

echo [mvnw] JDK      : !MVN_JAVA!

rem ---------------------------------------------------------------------------
rem  2. Buscar un Maven ya descargado en la carpeta del usuario.
rem
rem     El Maven Wrapper guarda la distribucion con esta forma:
rem       dists\apache-maven-3.9.9-bin\<carpeta>\apache-maven-3.9.9\bin\mvn.cmd
rem     y tambien existe la forma mas simple:
rem       dists\apache-maven-3.9.9\bin\mvn.cmd
rem     Se buscan las dos.
rem ---------------------------------------------------------------------------
set "MVN_CMD="

for /d %%A in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-*") do (

    rem Forma simple: dists\apache-maven-X\bin\mvn.cmd
    if exist "%%~fA\bin\mvn.cmd" (
        if not defined MVN_CMD set "MVN_CMD=%%~fA\bin\mvn.cmd"
    )

    rem Forma del wrapper: dists\apache-maven-X\<hash>\apache-maven-X\bin\mvn.cmd
    for /d %%B in ("%%~fA\*") do (
        if not defined MVN_CMD (
            if exist "%%~fB\bin\mvn.cmd" set "MVN_CMD=%%~fB\bin\mvn.cmd"
        )
        for /d %%C in ("%%~fB\apache-maven-*") do (
            if not defined MVN_CMD (
                if exist "%%~fC\bin\mvn.cmd" set "MVN_CMD=%%~fC\bin\mvn.cmd"
            )
        )
    )
)

if not defined MVN_CMD (
    echo [mvnw] Maven no esta en este equipo. Se descarga la version !MVN_VERSION!...
    set "MVN_DIST=!USERPROFILE!\.m2\wrapper\dists\apache-maven-!MVN_VERSION!-bin\descarga"
    if not exist "!MVN_DIST!" mkdir "!MVN_DIST!"
    powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $d='!MVN_DIST!'; $t=Join-Path $d 'maven-!MVN_VERSION!-bin'; if(!(Test-Path $t)){ $z=Join-Path $d 'maven.zip'; Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/!MVN_VERSION!/apache-maven-!MVN_VERSION!-bin.zip' -OutFile $z; Expand-Archive -Path $z -DestinationPath $d -Force; Remove-Item $z }"
    if exist "!MVN_DIST!\maven-!MVN_VERSION!-bin\bin\mvn.cmd" (
        set "MVN_CMD=!MVN_DIST!\maven-!MVN_VERSION!-bin\bin\mvn.cmd"
    )
)

if not defined MVN_CMD (
    echo.
    echo [mvnw] ERROR: no se pudo conseguir Maven.
    echo.
    exit /b 1
)

echo [mvnw] Maven    : !MVN_CMD!
echo [mvnw] Proyecto : %~dp0pom.xml
echo.

rem ---------------------------------------------------------------------------
rem  3. Ejecutar Maven
rem ---------------------------------------------------------------------------
set "JAVA_HOME=!MVN_JAVA!"
call "!MVN_CMD!" -f "%~dp0pom.xml" %*
exit /b %ERRORLEVEL%
