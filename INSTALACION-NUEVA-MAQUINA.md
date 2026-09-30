# Instalación en una máquina nueva

Guía para alguien que copia la carpeta `minisistema` a una laptop que **nunca
ha visto este proyecto** y tiene que dejarla funcionando desde cero.

Esta guía es solo para la **primera instalación**. Para el uso diario, mira
[INSTRUCCIONES.md](INSTRUCCIONES.md).

---

## 0. Antes de empezar

### Qué hay que copiar

La **carpeta `minisistema` completa**, tal cual. No hay que separar nada ni
renombrar archivos.

### Dónde ponerla

Cualquier sitio sirve. El proyecto **no tiene rutas absolutas fijadas**: todos
los scripts se localizan a sí mismos con `%~dp0` (batch) y con
`__file__` (Python), así que funciona dentro de `C:\...`, de `D:\...` o de
una carpeta de OneDrive.

Dos consejos para evitar sorpresas:

- Evita carpetas con tildes o `ñ` en el nombre (`Documentos\Materias\Base de datos 3`).
- Las carpetas **con espacios sí funcionan**. En la máquina de desarrollo está
  en `D:\Semestre_2_2026\Base de datos 3\minisistema` y nunca dio problemas.

### La carpeta `target` (64 MB) es opcional

`2-sistema-flink-java\target\` son 45 archivos `.jar` que Maven ya descargó.

**Puedes borrarla** para ahorrar 64 MB. Maven la vuelve a crear sola en el
paso 5. Es lo más limpio para una copia nueva.

### Lo que NO viene en la carpeta

La carpeta trae el código, pero **no** trae las herramientas ni los datos.
Falta esto, y es lo que instala esta guía:

| Falta | Qué es |
|---|---|
| MongoDB Server | El motor de base de datos |
| Los 250 países | Están en la base de datos de la máquina vieja, no en la carpeta |
| JDK 21 | Para compilar y correr el job de Flink |
| Python 3.9+ | Para servir la interfaz web |
| Las dependencias de Maven | Se descargan en el paso 5 |

---

## 1. Orden de instalación

Instálalos en este orden. Cada uno depende del anterior en la forma en que se
va a verificar.

| # | Qué | Tiempo | Pasos |
|---|---|---|---|
| 1 | MongoDB Server 8.x | 5 min | [Paso 2](#2-instalar-mongodb-server-8x) |
| 2 | JDK 21 (Temurin) | 5 min | [Paso 3](#3-instalar-el-jdk-21) |
| 3 | Python 3.9 o superior | 5 min | [Paso 4](#4-instalar-python) |
| 4 | Dependencias de Maven | 3-10 min | [Paso 5](#5-primer-build--paso-que-mas-falla) |
| 5 | Cargar los 250 países | 1 min | [Paso 6](#6-cargar-los-250-pa%C3%ADses) |
| 6 | Correr el job de Flink | 1 min | [Paso 7](#7-correr-el-job-de-flink) |
| 7 | Arrancar la web | 10 s | [Paso 8](#8-arrancar-la-interfaz-web) |

**Necesitas internet en los pasos 1, 2, 4 y 5.** Los pasos 3, 6, 7 y 8
funcionan sin conexión.

---

## 2. Instalar MongoDB Server 8.x

### 2.1 Descargar

Ve a la página oficial y descarga el instalador para Windows:

```
https://www.mongodb.com/try/download/community
```

El archivo será un `.msi` (por ejemplo `mongodb-windows-x86_64-8.0.x.x-signed.msi`).

**No el "Community Server" del ZIP ni el tarball**: necesitamos el `.msi`
porque es el que instala el servicio de Windows que usa el proyecto.

### 2.2 Instalar

Doble clic en el `.msi` y sigue el asistente. En la pantalla de configuración
importante:

| Opción | Qué marcar | Por qué |
|---|---|---|
| **Install MongoDB as a Service** | **Marcado** | El proyecto comprueba que el servicio se llama `MongoDB`. Si lo desmarcas, hay que arrancarlo a mano cada vez. |
| **Service Name** | Déjalo como `MongoDB` | Es el nombre que espera `Get-Service MongoDB`. |
| **Run as Network Service user** | **Marcado** | Es lo que evita el error de permisos de la [sección 10](#10-problemas-frecuentes). |
| **Install MongoDB Compass** | **Desmarcado** | Es una app gráfica que no se usa aquí. Desmarcarla ahorra ~500 MB. |

El instalador deja la base escuchando en el puerto **27017** en
`127.0.0.1`, que es justo lo que espera `config.properties`.

### 2.3 Verificar

Abre **PowerShell** (no CMD) y comprueba:

```powershell
Get-Service MongoDB
```

Debe decir `Running`. Si dice `Stopped`, arranca el servicio:

```powershell
Start-Service MongoDB
```

Y comprueba que el puerto responde:

```powershell
Test-NetConnection -ComputerName localhost -Port 27017
```

`TcpTestSucceeded : True` significa que MongoDB está escuchando.

> Todavía no hay datos. La base está vacía, y eso es normal: los 250 países
> se cargan en el [paso 6](#6-cargar-los-250-pa%C3%ADses).

---

## 3. Instalar el JDK 21

Se usa Java 17 o superior. Recomendado: **Temurin 21** (gratuito, de Adoptium).

### 3.1 Descargar

```
https://adoptium.net/temurin/releases/?version=21
```

Elige el de **Windows x64 JDK** en formato `.msi` e instálalo.

### 3.2 Por qué importa instalarlo bien

En la máquina de desarrollo, la variable `JAVA_HOME` apuntaba a un **JRE 8
roto** que no traía Java funcional. Por eso los scripts del proyecto
(`run.bat` y `mvnw.cmd`) **ignoran el `java` del PATH** y buscan por su cuenta
un JDK 11 o superior en:

1. La variable `JAVA_HOME`, si contiene un `javac.exe`.
2. `C:\Program Files\Java\jdk-*`
3. `C:\Program Files\Eclipse Adoptium\jdk-*`

Si instalas Temurin con la ruta por defecto, el paso 3 de esa lista lo
encuentra solo y no hay que configurar nada.

### 3.3 Verificar

```powershell
Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-*" -Directory
```

Si sale una carpeta (por ejemplo `jdk-21.0.12`), el proyecto lo va a encontrar.
No hace falta que `java -version` funcione en el PATH.

Si lo instalaste en otra ruta y no aparece, define `JAVA_HOME` para esta
sesión de PowerShell:

```powershell
$env:JAVA_HOME = "C:\ruta\real\del\jdk"
```

---

## 4. Instalar Python

Cualquier **Python 3.9 o superior** sirve. El mínimo lo impone `pymongo`, no
el código del proyecto.

### 4.1 Descargar

```
https://www.python.org/downloads/windows/
```

Descarga el instalador del 64 bits.

### 4.2 Marcar estas dos casillas

Es el error más común al instalar Python en Windows:

- [x] **Add python.exe to PATH**
- [x] **Install launcher for all users** (o al menos, para el usuario actual)

Sin la primera, `python` no se reconoce en PowerShell y el paso 8 falla.

### 4.3 Instalar la única dependencia

Abre PowerShell, ve a la carpeta del proyecto y ejecuta:

```powershell
cd "C:\ruta\a\minisistema\3-interfaz-web-python"
pip install -r requirements.txt
```

Deberías ver una línea con `Successfully installed pymongo-...`.

### 4.4 Verificar

```powershell
python --version
python -c "import pymongo; print(pymongo.version)"
```

La primera debe dar 3.9 o más. La segunda debe imprimir un número.

---

## 5. Primer build — el paso que más falla

### 5.1 Qué pasa

`run.bat` (línea 52) y `compilar.bat` (línea 16) compilan así:

```bat
call mvnw.cmd -o -q compile
```

El `-o` le dice a Maven que trabaje **sin internet**. En la máquina de
desarrollo funcionaba porque la carpeta `~/.m2\repository` ya tenía los ~30
artefactos de Flink descargados.

**En una máquina nueva esa carpeta está vacía**, así que Maven no puede
conseguir Flink y el build falla.

### 5.2 La solución

Hacer **un solo build con internet**, sin el `-o`. Es un paso manual, una
única vez. Después, `run.bat` vuelve a funcionar sin conexión.

Ve a la carpeta del proyecto de Java:

```powershell
cd "C:\ruta\a\minisistema\2-sistema-flink-java"
.\mvnw.cmd compile
```

Qué va a pasar:

1. `mvnw.cmd` busca el JDK (lo encuentra en el paso 3).
2. **Descarga Apache Maven 3.9.9** en `C:\Users\<tu_usuario>\.m2\wrapper\`.
   Solo en esta máquina. La primera vez puede tardar un poco.
3. **Descarga las dependencias de Flink** desde Maven Central: unas 30
   bibliotecas, alrededor de 60 MB.
4. Compila y copia los 45 `.jar` a `target\lib\`.

Tarda entre **3 y 10 minutos** según la conexión. Es normal que aparezcan
muchas líneas de descarga:

```
[INFO] Downloading from central: https://repo.maven.apache.org/maven2/...
[INFO] Downloaded from central: ... flink-runtime-1.20.1.jar
...
[INFO] BUILD SUCCESS
```

### 5.3 Verificar

```powershell
Get-ChildItem ".\target\lib" -Filter *.jar | Measure-Object
```

Debe devolver `Count : 45`. Si es así, ya puedes usar `run.bat` normalmente
y **sin internet** a partir de ahora.

---

## 6. Cargar los 250 países

### 6.1 Por qué hace falta

Los 250 documentos están en la base de datos de la máquina de desarrollo, no
en la carpeta. En la laptop nueva `Mundo.paises` está **vacía** y el sistema
no mostraría nada.

Lo que sí viene en la carpeta es el archivo `paises_mongo.json`, que tiene los
250 países listos. Este paso lo mete en MongoDB.

### 6.2 Cargar los datos

`mongoimport` no viene con el instalador de MongoDB 8.x, así que se usa
`pymongo`, que ya instalaste en el paso 4.

**Sitúate en la carpeta `minisistema`** (donde está el JSON) y pega este
bloque en PowerShell:

```powershell
@'
import json, pymongo

datos = json.load(open("paises_mongo.json", encoding="utf-8"))
c = pymongo.MongoClient("mongodb://localhost:27017")["Mundo"]["paises"]

c.delete_many({})
print("insertados:", len(c.insert_many(datos).inserted_ids))
print("total ahora:", c.count_documents({}))
'@ | Set-Content -Path cargar.py -Encoding utf8

python cargar.py
```

Debe imprimir:

```
insertados: 250
total ahora: 250
```

El `encoding="utf-8"` es importante: sin él, los acentos de los nombres
(`Afganistán`, `Rumanía`) y los símbolos de las monedas (`ƒ`, `¥`) salen
rotos.

### 6.3 Nota

- El script **borra** `Mundo.paises` antes de insertar, así que correrlo dos
  veces no duplica nada. Puedes repetirlo sin miedo.
- **No toca** `Mundo.resumen_regiones`. Eso lo rellena el job de Flink del
  paso 7.
- Al terminar puedes borrar el archivo temporal:

```powershell
Remove-Path cargar.py -ErrorAction SilentlyContinue
```

O simplemente:

```powershell
del cargar.py
```

### 6.4 Verificar los tipos

Los datos se guardan con los mismos tipos que en la máquina de desarrollo:
250 poblaciones enteras, 3 superficies decimales y 180 densidades decimales.
Esto importa porque el código Java los lee de forma tolerante
(`Pais.java:89` y `:106`) precisamente por esa mezcla.

---

## 7. Correr el job de Flink

```powershell
cd "C:\ruta\a\minisistema\2-sistema-flink-java"
.\run.bat
```

O doble clic en `run.bat`.

Qué hace: compila (ahora ya en modo offline, rápido), levanta el mini-cluster
de Flink, lee los 250 países repartidos en 4 subtareas, los agrupa por región
y escribe 6 resultados en `Mundo.resumen_regiones`.

Debe terminar con:

```
[5] Job terminado en 2332 ms.
    escrito en Mundo.resumen_regiones

--------------------------------------------------------
  REGION      PAISES         POBLACION     SUPERFICIE  GRANDES  PAIS MAS POBLADO
--------------------------------------------------------
  Africa          59        1263484702       30318417        8  Sudán (SDN)
  Americas        56        1023192295       42077922        4  Brasil (BRA)
  Antarctic        5           1278000       14012111        0  Antártida (ATA)
  Asia            50        3315885956       32138141       12  China (CHN)
  Europe          53        1277756183       23022998        1  Rusia (RUS)
  Oceania         27          39034101        8515313        0  Australia (AUS)
--------------------------------------------------------
  TOTAL          250        6920631237      150084902       25
--------------------------------------------------------

[OK] El job termino. Los resultados estan en Mundo.resumen_regiones
```

Si los 6 números de `PAISES` suman 250, el pipeline está bien.

Los cuatro `[WARN]` que salen antes son normales y están explicados en
[GUIA-FLINK.md](GUIA-FLINK.md).

Presiona una tecla para cerrar la ventana.

---

## 8. Arrancar la interfaz web

Abre **otra** ventana de PowerShell y deja esta **abierta**:

```powershell
cd "C:\ruta\a\minisistema\3-interfaz-web-python"
python servidor.py
```

Debe salir:

```
  [OK] MongoDB 8.3.8 en localhost:27017
       paises en la base: 250
  [OK] Job de Flink ya corrio: hay 6 regiones

  Abre en el navegador:  http://localhost:8000
```

> **Esta ventana no se puede cerrar.** Si la cierras, el servidor muere y la
> página deja de cargar. Déjala minimizada. Para detenerlo: `Ctrl+C`.

En el navegador, en otra pestaña:

```
http://localhost:8000
```

El indicador de arriba a la derecha debe salir en verde:
`MongoDB 8.3.8 · 250 paises · Flink: 6 regiones`.

Ya está. Las 3 operaciones están en las pestañas **Fichas**, **Comparar** y
**Buscar**. La cuarta, **Resumen Flink**, muestra los 6 resultados del paso 7.

---

## 9. Resumen: los comandos del día a día

Copia este bloque cuando vuelvas a usar el proyecto en esta laptop.

```powershell
# 1. MongoDB (si no arranco solo al encender)
Get-Service MongoDB
Start-Service MongoDB

# 2. El job de Flink
#    Opcional: solo hace falta si cambiaste config.properties
cd "C:\ruta\a\minisistema\2-sistema-flink-java"
.\run.bat

# 3. La interfaz web (deja esta ventana abierta)
cd "C:\ruta\a\minisistema\3-interfaz-web-python"
python servidor.py

# 4. Abrir
#    http://localhost:8000
```

Si MongoDB ya está instalado y arrancado, y el job de Flink ya corrió una
vez, **el día a día es solo el paso 3**.

---

## 10. Problemas frecuentes

### El puerto 27017 ya está ocupado

MongoDB no arrancó porque otra cosa usa el puerto:

```powershell
Get-NetTCPConnection -LocalPort 27017 -State Listen
```

Si lo ocupa un `mongod.exe` tuyo, hay dos servicios o dos instalaciones
distintas. Si lo ocupa otro programa, hay que mover MongoDB de puerto, lo que
también obliga a cambiar `mongo.uri` en `config.properties` y la URI de
`consultas.py`.

### El servicio MongoDB no arranca

```powershell
Get-Service MongoDB
Start-Service MongoDB
```

Si sigue sin arrancar, mira el log:

```
C:\Program Files\MongoDB\Server\<version>\log\mongod.log
```

El error más común es un `dbPath` sin permisos o ya ocupado por una
instalación anterior.

### `Access is denied` al arrancar el servicio

Significa que el servicio está configurado para correr con una cuenta local en
lugar de la cuenta de servicio. Se arregla en el instalador: desmarca el
servicio, desinstala, reinstala y marca **"Run as Network Service user"**.

### `python no se reconoce como comando

Falta la casilla **"Add python.exe to PATH"** del instalador. Vuelve a
ejecutarlo y márcala, o llama al ejecutable con la ruta completa.

### `'python' no se reconoce como cmdlet` pero existe Python

Prueba con el lanzador: `py --version` y `py cargar.py`.

### `mvnw.cmd` dice que no encuentra un JDK

O no instalaste el JDK (paso 3), o quedó en una ruta que el script no revisa.
Comprueba:

```powershell
Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-*" -Directory
Get-ChildItem "C:\Program Files\Java\jdk-*" -Directory
```

Si no sale nada, define `JAVA_HOME` como se explica en la
[sección 3.3](#33-verificar).

### Maven falla con errores de descarga

Casi siempre es falta de internet en el paso 5. Revisa que no haya un proxy
corporativo bloqueando `repo.maven.apache.org`. Si estás en una red de empresa,
pide que allowlisten ese dominio.

### Los acentos salen rotos en la web

Significa que los datos se cargaron sin `encoding="utf-8"`. Vuelve a correr el
bloque del [paso 6.2](#62-cargar-los-datos) tal cual está; como borra antes de
insertar, deja los datos correctos.

### La página abre pero sale «El job de Flink todavía no ha corrido»

Falta el paso 7. Corre `run.bat` y recarga la página.

### La página no carga nada

Comprueba primero que la ventana del servidor sigue abierta:

```powershell
Invoke-WebRequest http://localhost:8000/api/estado -UseBasicParsing |
    Select-Object -ExpandProperty Content
```

Debe responder algo como:

```json
{"mongo": true, "paises": 250, "regiones": 6, "jobCorrido": true, "servidor": "8.3.8"}
```

Si `"paises": 0`, falta el paso 6.

---

## 11. Documentos relacionados

| Documento | Para qué |
|---|---|
| [INSTRUCCIONES.md](INSTRUCCIONES.md) | Cómo usar el sistema en el día a día y las 3 operaciones |
| [GUIA-FLINK.md](GUIA-FLINK.md) | Dónde se aplica Apache Flink, dónde están las conexiones a MongoDB y qué se espera como resultado |
