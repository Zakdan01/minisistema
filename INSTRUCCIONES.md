# Instrucciones — Sistema Mondo

Guía de arranque **manual**. No hay ningún lanzador de un clic: se levantan
dos cosas por separado, el job de Flink y el servidor web.

---

## 1. Qué hace el sistema

El sistema tiene dos partes que se usan por separado:

| Parte | Qué hace | Cuándo corre |
|---|---|---|
| **Job de Apache Flink** | Lee los 250 países de MongoDB y calcula los totales por región. Los deja guardados en `Mundo.resumen_regiones`. | Solo cuando tú lo lanzas |
| **Interfaz web** | Muestra los países en fichas, deja comparar poblaciones y buscar por atributos. | Siempre que esté encendida |

**Importante:** Apache Flink **no** responde a las búsquedas ni a las
comparaciones. Solo calcula los 6 resúmenes por región. Todo lo demás lo
hace MongoDB directamente desde Python. La razón está explicada en
[GUIA-FLINK.md](GUIA-FLINK.md).

Las 3 operaciones de la interfaz:

1. **Fichas** — ver los 250 países con su capital, población, superficie, densidad, monedas e idiomas.
2. **Comparar poblaciones** — elegir hasta 40 países y ver barras, porcentajes y cuántas veces tiene el mayor que el menor.
3. **Buscar** — filtrar por texto libre, región, moneda, idioma, superficie y población. Todos los filtros se combinan a la vez.

---

## 2. Requisitos

| Necesitas | Versión | Nota |
|---|---|---|
| MongoDB | 8.3.8 | Servicio de Windows, arranca solo. No hay que hacer nada. |
| JDK | 21 o superior | En `C:\Program Files\Java\jdk-21.0.12` |
| Maven | 3.9.9 | Ya descargado en la carpeta del usuario. `mvnw.cmd` lo encuentra solo. |
| Python | 3.14.7 | Con `pymongo` instalado. |
| Navegador | Chrome, Edge o Firefox | Cualquiera moderno. |

El sistema **no necesita internet**: todas las dependencias de Flink y Maven
ya están descargadas en la caché de este equipo.

### Comprobar que Python tiene pymongo

```powershell
python -c "import pymongo; print(pymongo.version)"
```

Si sale un número, está todo listo. Si da error, instala la dependencia:

```powershell
cd "D:\Semestre_2_2026\Base de datos 3\minisistema\3-interfaz-web-python"
pip install -r requirements.txt
```

---

## 3. Estructura de carpetas

```
minisistema\
│
├── paises_mongo.json          Datos originales (250 países). NO se modifica.
│
├── INSTRUCCIONES.md           Este archivo.
├── GUIA-FLINK.md              Dónde y cómo se aplica Apache Flink.
│
├── 2-sistema-flink-java\      EL JOB DE FLINK (Java)
│   ├── run.bat                ← compila y ejecuta el job
│   ├── compilar.bat           ← solo compila
│   ├── mvnw.cmd               lanzador de Maven
│   ├── pom.xml                dependencias
│   └── src\main\java\com\minisistema\
│       ├── AnalisisFlinkBatch.java     el pipeline completo
│       ├── Config.java                 lee config.properties
│       ├── ComprobarReparto.java       prueba del reparto
│       ├── flink\
│       │   ├── MongoPaisesInputFormat.java   LA FUENTE
│       │   ├── MongoResumenOutputFormat.java EL SUMIDERO
│       │   └── MongoSplit.java                un tramo de la fuente
│       └── modelo\
│           ├── Pais.java                     los datos de un país
│           └── IndicadoresRegion.java        los totales acumulados
│
└── 3-interfaz-web-python\     LA INTERFAZ (Python)
    ├── servidor.py            servidor HTTP en el puerto 8000
    ├── consultas.py           las consultas a MongoDB
    ├── requirements.txt
    └── static\
        ├── index.html         la página
        ├── estilos.css
        └── app.js             toda la lógica del navegador
```

---

## 4. Arranque manual

Son 4 pasos y en este orden. Los pasos 1 y 2 se dejan en **ventanas
separadas**.

### Paso 0 — MongoDB

MongoDB es un servicio de Windows que arranca solo con el equipo. Para
comprobar que está arriba:

```powershell
Get-Service MongoDB
```

Debe decir `Running`. Si dijera `Stopped`:

```powershell
Start-Service MongoDB
```

### Paso 1 — El job de Apache Flink

Doble clic en:

```
2-sistema-flink-java\run.bat
```

O desde PowerShell:

```powershell
cd "D:\Semestre_2_2026\Base de datos 3\minisistema\2-sistema-flink-java"
.\run.bat
```

Qué hace, por dentro:

1. Busca un JDK 11 o superior.
2. Compila el proyecto con Maven y copia las 45 dependencias en `target\lib`.
3. Arranca Java con los 13 flags `--add-opens` que Flink necesita.
4. Ejecuta `com.minisistema.AnalisisFlinkBatch`.
5. Muestra la tabla con los 6 resultados.

Tarda alrededor de **un minuto**. Cuando termina verás:

```
[OK] El job termino. Los resultados estan en Mundo.resumen_regiones

Presione una tecla para continuar . . .
```

Presiona una tecla para cerrar la ventana.

Los 4 `[WARN]` que salen son normales y explicados en
[la sección 7](#7-problemas-frecuentes).

> **¿Necesito correrlo siempre?** No. Los resultados quedan guardados en
> MongoDB. Solo vuelve a correrlo si cambias `config.properties` (por
> ejemplo el paralelismo) o si quieres regenerar los resúmenes.

### Paso 2 — El servidor web

Abre **otra** ventana de PowerShell:

```powershell
cd "D:\Semestre_2_2026\Base de datos 3\minisistema\3-interfaz-web-python"
python servidor.py
```

Verás un aviso:

```
  [OK] MongoDB 8.3.8 en localhost:27017
       paises en la base: 250
  [OK] Job de Flink ya corrio: hay 6 regiones

  Abre en el navegador:  http://localhost:8000
```

### ⚠️ La ventana del servidor NO se puede cerrar

**Este es el paso donde casi todo el mundo se atora.** La ventana del
servidor debe quedar **abierta** mientras uses la página. Si la cierras, el
servidor muere y la página deja de cargar.

Déjala minimizada, no la cierres. Para detener el servidor: `Ctrl+C`.

### Paso 3 — Abrir la interfaz

En el navegador, en otra pestaña:

```
http://localhost:8000
```

---

## 5. Cómo detener todo

| Qué | Cómo |
|---|---|
| El servidor web | `Ctrl+C` en la ventana de `servidor.py` |
| El servidor web (si la ventana se perdió) | `Stop-Process -Id <número>` — el número lo ves con `Get-Process python` |
| El job de Flink | Ya terminó solo. No queda ningún proceso. |
| MongoDB | **Déjalo corriendo.** Es un servicio del sistema. |

El job de Flink no es un servidor: arranca, corre y termina. No hay nada
que apagar.

---

## 6. Las 3 operaciones

### Fichas

Las 250 fichas con todos los datos. Dos filtros rápidos:

- El cuadro de texto busca en nombre, nombre oficial, capital, región y subregión.
- El desplegable filtra por región.

### Comparar poblaciones

1. Escribe en el cuadro para localizar países (muestra hasta 60 resultados).
2. Marca las casillas de los que quieras, hasta 40.
3. Pulsa **Comparar**.

Sale un resultado con: barras proporcionales (verde el mayor, rojo el
menor), cuatro cajas de totales y una tabla con % sobre la comparación y %
sobre la población mundial.

Pulsa **Limpiar selección** para vaciar la lista.

### Buscar

Todos los filtros se combinan con **Y**. Ejemplos:

| Qué escribir | Resultado |
|---|---|
| `euro` en Moneda | 38 países de la zona euro |
| `Spanish` en Idioma | 24 países hispanohablantes |
| `1 000 000` en Superficie desde | 31 países de más de un millón de km² |
| `Doha` en Texto libre | 1: Catar (busca también en las capitales) |
| `euro` + `Asia` + `500000` | **0 resultados** — correcto, ningún país de Asia usa el euro |

El buscador delega el filtrado en MongoDB, no en el navegador. Los países
salen ordenados por población, de mayor a menor.

---

## 7. Problemas frecuentes

### Los 4 `[WARN]` de Flink

```
[WARN] DefaultDelegationTokenManager - No tokens obtained so skipping notifications
[WARN] WebMonitorUtils - Log file environment variable 'log.file' is not set.
[WARN] WebMonitorUtils - JobManager log files are unavailable in the web dashboard.
[WARN] DefaultDelegationTokenManager - Tokens update task not started...
```

**No son errores.** El job corre en un mini-cluster dentro de la propia JVM
y por eso:

- No hay Kerberos, así que no hay *delegation tokens* que pedir.
- No se levanta el dashboard web de Flink, así que no hay `log.file` que buscar.

El job termina bien. Si quisieras verlos, el archivo
`simplelogger.properties` está configurado para dejar pasar solo avisos.

### La superficie sale 150.084.902 y no 150.084.903

No es un error. Si sumas las 250 superficies directamente redondeando al
final, sale `150.084.903`. Pero Flink **redondea cada región por separado**
antes de sumar, y así queda `150.084.902`. Es aritmética por etapas, no un
dato perdido.

### `Address already in use` / el puerto 8000 está ocupado

Ya hay un servidor corriendo, o quedó uno huérfano:

```powershell
Get-NetTCPConnection -LocalPort 8000 -State Listen
Stop-Process -Id <el número que appeared>
```

### MongoDB no responde

```powershell
Get-Service MongoDB
Start-Service MongoDB
```

Si sigue sin responder, el puerto 27017 está ocupado por otro programa.

### `ModuleNotFoundError: No module named 'pymongo'`

Falta la dependencia. Ver [la sección 2](#2-requisitos).

### La página dice «El job de Flink todavía no ha corrido»

MongoDB no tiene resultados en `resumen_regiones`. Corre el paso 1
(`run.bat`) y recarga la página.

### La página abre pero está vacía

Comprueba que la ventana del servidor sigue abierta y que devuelve datos:

```powershell
Invoke-WebRequest http://localhost:8000/api/estado -UseBasicParsing | Select-Object -ExpandProperty Content
```

Debe responder algo como:

```json
{"mongo": true, "paises": 250, "regiones": 6, "jobCorrido": true, "servidor": "8.3.8"}
```

### Error de compilación en Java

`JAVA_HOME` apunta a un JRE 8 roto en este equipo, pero `mvnw.cmd` y
`run.bat` lo ignoran y buscan un JDK 11 o superior por su cuenta. Si aun
así falla, comprueba que existe `C:\Program Files\Java\jdk-21.0.12`.

---

## 8. Verificar que todo fue bien

El job debe imprimir esta tabla. Si los números coinciden, el pipeline
completo funcionó:

```
  REGION      PAISES         POBLACION     SUPERFICIE  GRANDES  PAIS MAS POBLADO
  Africa          59        1263484702       30318417        8  Sudán (SDN)
  Americas        56        1023192295       42077922        4  Brasil (BRA)
  Antarctic        5           1278000       14012111        0  Antártida (ATA)
  Asia            50        3315885956       32138141       12  China (CHN)
  Europe          53        1277756183       23022998        1  Rusia (RUS)
  Oceania         27          39034101        8515313        0  Australia (AUS)
  TOTAL          250        6920631237      150084902       25
```

Además, en la web:

- El indicador verde de arriba debe decir `MongoDB 8.3.8 · 250 paises · Flink: 6 regiones`.
- La pestaña **Resumen Flink** debe mostrar las mismas 6 filas.

---

## 9. Nota sobre los datos

Las poblaciones de este conjunto de datos **no son reales**: están estimadas
a partir de la superficie, por eso Rusia aparece con 897 millones de
habitantes y Aruba con 3.465. Los países, capitales, fronteras, monedas e
idiomas sí son reales.

Es una limitación de los datos de origen, no del sistema.

---

Ver [GUIA-FLINK.md](GUIA-FLINK.md) para saber exactamente dónde se aplica
Apache Flink y dónde están cada una de las conexiones a la base de datos.
