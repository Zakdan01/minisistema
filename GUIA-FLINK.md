# Guía técnica — Dónde y cómo se aplica Apache Flink

Este documento responde tres preguntas concretas:

1. ¿Qué calcula Apache Flink y qué **no** calcula?
2. ¿En qué archivo y línea exacta se aplica cada parte de Flink?
3. ¿Dónde están todas las conexiones a MongoDB?

---

## 1. Qué calcula Flink y qué no

| Cálculo | Quién lo hace | Por qué |
|---|---|---|
| Totales por región (6 filas) | **Apache Flink** | Es la parte de agregación por lotes, que es la que necesita distribuirse |
| Población total, superficie total, población máxima, país más poblado, nº de países grandes | **Apache Flink** | Sale del mismo `reduce` |
| Fichas de los 250 países | MongoDB, desde Python | No hay agregación: es una lectura simple |
| Comparar poblaciones | MongoDB, desde Python | Es un `$in` de 40 códigos y una suma |
| Buscar por moneda, idioma, región, superficie | MongoDB, desde Python | Filtros delegated al motor de la base |

**Por qué Flink no atiende las búsquedas:** lanzarlo por cada búsqueda
costaría un minuto de arranque (compilar, levantar el mini-cluster, leer los
250 documentos, escribir resultados) para devolver algo que un `find` con
`$regex` resuelve en milisegundos. Flink se usa para el trabajo de
agregación por lotes, que es lo que de verdad necesita ser distribuido.

La separación queda así:

```
Job de Flink   = ESCRIBE en Mundo.resumen_regiones   (nunca toca Mundo.paises)
Interfaz web   = LEE de Mundo.paises y de Mundo.resumen_regiones
```

---

## 2. El flujo de datos completo

```
        Mundo.paises                          MongoDB 8.3.8
        250 documentos                        colección "paises"
        _id = "ABW" ... "ZWE"
              │
              │  createInputSplits()  — se calcula UNA vez, en el cliente
              │  cuenta países por letra inicial y saca 4 cortes
              ▼
   ┌──────────────────────────────────────────────┐
   │  MongoPaisesInputFormat  (RichInputFormat)   │
   │  4 subtareas · 4 conexiones independientes    │
   │                                              │
   │   subtarea 0  [A..D)   59 países  ┐          │
   │   subtarea 1  [D..L)   65 países  │ map()    │
   │   subtarea 2  [L..R)   63 países  ├──────►  250 IndicadoresRegion
   │   subtarea 3  [R..ZZ)  63 países  ┘          │  (uno por país)
   └──────────────────────────────────────────────┘
              │
              │  groupBy(region)  →  250 registros se agrupan en 6 claves
              ▼
   ┌──────────────────────────────────────────────┐
   │  reduce(IndicadoresRegion::sumar)            │
   │  fusiona los acumulados hasta dejar 6         │
   └──────────────────────────────────────────────┘
              │
              │  output(...)
              ▼
   ┌──────────────────────────────────────────────┐
   │  MongoResumenOutputFormat  (RichOutputFormat)│
   │  1 conexión · replaceOne con upsert          │
   └──────────────────────────────────────────────┘
              │
              ▼
        Mundo.resumen_regiones
        6 documentos
              │
              │  SOLO LECTURA
              ▼
        consultas.py  →  1 cliente pymongo
              │
              ▼
        servidor.py  →  http://localhost:8000
              │
              ▼
        index.html + app.js
```

---

## 3. Las 5 etapas del job, con archivo y línea

Todo está en **`2-sistema-flink-java\src\main\java\com\minisistema\AnalisisFlinkBatch.java`**.

### ⚠️ Lo primero que hay que entender

**Flink no hace absolutely nada hasta la línea 127.** Todo lo anterior solo
arma un plan de ejecución en memoria. Ni una sola consulta a MongoDB se
envía hasta que se llama a `execute()`.

| Línea | Etapa | Código |
|---|---|---|
| **70-71** | **Arranca Flink** | `ExecutionEnvironment.createLocalEnvironment(paralelismo)` |
| **83-85** | **1. Fuente** | `entorno.createInput(new MongoPaisesInputFormat(...), TypeInformation.of(Pais.class))` |
| **97-99** | **2. map** | `.map(p -> IndicadoresRegion.desdePais(p, umbral))` |
| **107-110** | **3+4. groupBy y reduce** | `.groupBy(IndicadoresRegion::getRegion).reduce(IndicadoresRegion::sumar)` |
| **119-120** | **5. Sumidero** | `.output(new MongoResumenOutputFormat(...))` |
| **127** | **Dispara todo** | `entorno.execute("agregar-paises-por-region")` |

### Detalle de cada una

**Arranque (líneas 70-71).** `createLocalEnvironment(4)` levanta un
*mini-cluster* dentro de la propia JVM del programa. No hay `flink run` ni
un clúster externo: las 4 subtareas son hilos. Por eso el proyecto necesita
la dependencia `flink-clients` en el `pom.xml` (línea 45), y no solo
`flink-java`.

**Fuente (líneas 83-85).** Se registra una fuente personalizada. Flink exige
un `TypeInformation` explícito para saber cómo serializar los objetos `Pais`.
La línea 89 (`paises.name("Fuente-MongoDB")`) solo le pone un nombre corto
al operador, porque Flink recorta los nombres de más de 80 caracteres.

**map (líneas 97-99).** Transformación 1 a 1: cada país se convierte en un
`IndicadoresRegion` con un solo país dentro. El cuerpo está en
`IndicadoresRegion.java:47` (`desdePais`), que llama a `agregar()` en la
línea 74.

**groupBy + reduce (líneas 107-110).** Aquí está **todo el cálculo**.
`groupBy(IndicadoresRegion::getRegion)` agrupa los 250 por región — 6
claves — y `reduce(IndicadoresRegion::sumar)` las fusiona. La función está
en `IndicadoresRegion.java:60`.

**Sumidero (líneas 119-120).** Los 6 registros se entregan al
`MongoResumenOutputFormat`, que los escribe. El job **no** trae los datos
de vuelta a Java para grabarlos a mano: Flink los entrega al sumidero y ahí
se guardan.

**execute (línea 127).** Lee un documento de la colección, abre las
conexiones, reparte el trabajo, hace los 6 reduces y escribe. La línea 128
mide cuánto tardó (típicamente unos 2.300 ms).

---

## 4. Dónde están las 6 conexiones a MongoDB

| # | Sitio | Archivo:línea | Para qué | ¿Es de Flink? |
|---|---|---|---|---|
| 1 | Lectura de la configuración | `config.properties:8-11` | La URI, la base y las colecciones | No |
| 2 | Cliente temporal de conteo | `MongoPaisesInputFormat.java:94` | Contar países por letra para calcular el reparto. Se cierra en la línea 116. | No, es del cliente |
| 3 | **La fuente** | `MongoPaisesInputFormat.java:155` | Cada subtarea abre **la suya** en `openInputFormat()`. Se cierra en la línea 202. | **Sí** |
| 4 | **El sumidero** | `MongoResumenOutputFormat.java:59` | El sink abre la suya en `open()`, la cierra en `close()` (línea 85). | **Sí** |
| 5 | Cliente auxiliar | `AnalisisFlinkBatch.java:147` | Borra los resultados del run anterior. | No |
| 6 | Cliente auxiliar | `AnalisisFlinkBatch.java:162` | Imprime la tabla final. | No |
| 7 | La web | `consultas.py:29` | Un único `MongoClient` para toda la aplicación. | No, es Python |

**La diferencia importante entre la 3 y la 4:** la fuente abre **4
conexiones** (una por subtarea) porque las 4 trabajan en paralelo y
MongoDB no permite compartir un cursor entre hilos. El sumidero abre
**una sola** porque escribe 6 registros muy despacio.

**Un solo cliente en Python** (línea 29) porque `pymongo` mantiene un pool
de conexiones por dentro y las peticiones del navegador se atienden en
hilos distintos con `ThreadingHTTPServer`. Es lo contrario del job: allí
cada subtarea necesita la suya, aquí se comparte un pool.

---

## 5. Cómo se reparte el trabajo entre subtareas

Todo pasa dentro de `MongoPaisesInputFormat`. Flink llama a los métodos en
este orden:

```
createInputSplits(4)          ← UNA vez, en el cliente
  getInputSplitAssigner(...)   ← reparte los splits entre subtareas
  openInputFormat()            ← en CADA subtarea: abre su conexión
  open(split)                  ← en CADA subtarea: abre SU cursor
  reachedEnd() / nextRecord()  ← se repite hasta agotar el cursor
  close() / closeInputFormat() ← cierra cursor y conexión
```

### El reparto por letra inicial

`createInputSplits()` (línea 88) hace lo siguiente:

1. Abre un cliente temporal (línea 94) y pide
   `c.distinct("_id")` (línea 100) para tener la lista de los 250 códigos.
2. Cuenta cuántos países empiezan por cada letra, de A a Z (líneas 99-105).
3. Calcula 4 puntos de corte repartiendo ese total en 4 partes iguales
   (línea 125).
4. Traduce cada corte a una letra y construye un `MongoSplit` por subtarea
   (líneas 135-141).
5. Cierra el cliente temporal (línea 116).

El reparto que sale con 250 países y 4 subtareas:

| Subtarea | Intervalo de letras | Países |
|---|---|---|
| 0 | `[A..D)` | 59 |
| 1 | `[D..L)` | 65 |
| 2 | `[L..R)` | 63 |
| 3 | `[R..ZZ)` | 63 |
| | **Total** | **250** |

Los intervalos van del `[A..M)` al `[R..ZZ)` porque los cortes caen donde
caen, siempre pegados: uno termina justo donde empieza el siguiente, sin
huecos ni solapamientos. Por eso el último es `ZZ` (línea 246) — es la
marca de "hasta el final".

Cada subtarea lee su tramo con un filtro de rango sobre `_id`
(líneas 219-224):

```java
{ "_id": { "$gte": letraInicio, "$lt": letraFin } }
```

Como los códigos van de A a Z, un intervalo de letras **es** un intervalo
de códigos, y MongoDB lo resuelve con el índice de `_id` sin recorrer la
colección entera.

### Un detalle que costó un error

Los límites **no** se calculan con `(long) (total * indice / subtareas)`,
porque `total * indice` con enteros se trunca y los últimos países se
pierden. Se usa `(long) Math.floor((double) total * indice / subtareas)`
(línea 125), que hace la división en decimal y después redondea hacia
abajo. Está explicado en `MongoSplit.java:15-25`.

### Reutilización de objetos

Flink pasa un objeto a reciclar en cada `nextRecord()` (línea 179). En vez
de crear 250 objetos `Pais`, se crean 4 (uno por subtarea, línea 157) y se
reutilizan: `Pais.copiarEn(reutilizar, cursor.next())`, implementado en
`Pais.java:66`.

### El reparto se puede comprobar

`ComprobarReparto.java` imprime el reparto y verifica que los 250 códigos
salen una sola vez. Útil para demostrar que no se pierde ni se duplica
ningún país.

---

## 6. Por qué el total sale siempre igual

Flink fusiona los acumulados en el orden que quiera, que depende del
planificador y de la red. Podría cambiar entre ejecuciones.

La respuesta está en `IndicadoresRegion.sumar()` (línea 60): usa **solo
operaciones conmutativas**.

| Campo | Operación | ¿Conmuta? |
|---|---|---|
| `cantidadPaises` | `+=` | Sí |
| `poblacionTotal` | `+=` | Sí |
| `superficieTotal` | `+=` | Sí |
| `cantidadGrandes` | `+=` | Sí |
| `poblacionMaxima` / `paisMasPoblado` | `Math.max` implícito (líneas 66-69) | Sí |

Sumar es conmutativo, y el máximo también: da igual si comparas A con B o
B con A. Por eso el resultado **no depende del paralelismo**: sale
exactamente igual con 4 subtareas que con 8.

Si aquí hubiera un `setTimeout` o un `reduce` no conmutativo, los totales
variarían entre ejecuciones. Ese es justo el requisito que hace que
`reduce` sea válido en Flink: **ser asociativo y conmutativo**.

La suma por región es la única aritmética real del proyecto. Todo lo demás
es lectura.

---

## 7. Configuración

Todo se cambia en
`2-sistema-flink-java\src\main\resources\config.properties`, que Maven copia
a `target\classes` y `Config.java:19` lee con el classloader.

| Línea | Propiedad | Por defecto | Para qué |
|---|---|---|---|
| 8 | `mongo.uri` | `mongodb://localhost:27017` | Dónde está la base |
| 9 | `mongo.base` | `Mundo` | Nombre de la base |
| 10 | `mongo.coleccionPaises` | `paises` | La fuente |
| 11 | `mongo.coleccionResumen` | `resumen_regiones` | El sumidero |
| **16** | `flink.paralelismo` | `4` | Número de subtareas |
| 20 | `umbralPoblacionGrande` | `50000000` | Un país "grande" supera esto |
| 25 | `mostrarResultadoConsola` | `true` | Si además imprime la tabla |

### Probar con otro paralelismo

Para cambiar `flink.paralelismo` a 8 y ver que los 6 totales **no cambian**,
solo edita la línea 16 y vuelve a correr `run.bat`. El reparto pasa a ser
31/31/31/31 y el resultado es idéntico. Es la demostración de que el
`reduce` es conmutativo.

---

## 8. El sumidero en detalle

`MongoResumenOutputFormat.java`:

| Línea | Qué hace |
|---|---|
| 58 | `open(int numTasks, int numSplitsTotal)` — la firma que pide Flink 1.20 |
| 59 | Abre la conexión |
| 65-82 | `writeRecord()` — por cada región construye el documento |
| 70 | `Math.round()` de la superficie, que por eso se guarda entera |
| 75-79 | `replaceOne` con `upsert(true)` en vez de `insertOne` |
| 85 | `close()` — cierra la conexión |

**Por qué `replaceOne` y no `insertOne`:** si el job se corre dos veces, con
`insertOne` quedarían 12 documentos duplicados. Con `upsert` cada región se
actualiza en su sitio y la colección siempre tiene 6.

De todos modos, `AnalisisFlinkBatch.java:146-158` borra la colección
resumen antes de empezar, así que el resultado de cada ejecución es limpio.

---

## 9. Estructura de las clases

```
com.minisistema\
│
├── AnalisisFlinkBatch.java     El pipeline. Los 6 puntos donde se aplica Flink.
├── Config.java                 Lee config.properties del classpath.
├── ComprobarReparto.java       Prueba de que los splits no pierden ni duplican.
│
├── flink\                      LA INTEGRACIÓN CON FLINK
│   ├── MongoPaisesInputFormat.java   Fuente. Extiende RichInputFormat<Pais, MongoSplit>
│   ├── MongoResumenOutputFormat.java Sumidero. Extiende RichOutputFormat
│   └── MongoSplit.java                Un tramo. Implementa InputSplit (Serializable)
│
└── modelo\                     LOS DATOS
    ├── Pais.java                    5 campos de los 12 del documento
    └── IndicadoresRegion.java       El acumulado que viaja por el reduce
```

### `Pais` y los tipos mezclados de MongoDB

`Pais.java` solo lee 5 de los 12 campos, porque es lo único que el cálculo
necesita. Los otros 7 los usa la web.

El detalle importante: los números en MongoDB **no tienen un tipo uniforme**.

| Campo | `int` | `double` |
|---|---|---|
| `poblacion` | 250 | 0 |
| `superficie_km2` | 247 | 3 |
| `densidad` | 70 | 180 |

Un cast directo —`(int) documento.get("superficie_km2")`— lanzaría
`ClassCastException` en esos 3 países. Por eso todo se lee con
`numeroLargo()` y `numeroDecimal()` (líneas 89 y 106), que toleran ambos
tipos.

El mismo problema aparece al leer el resultado: en
`AnalisisFlinkBatch.java:182-185` se usa `((Number) d.get(...)).longValue()`
en vez de `d.getLong(...)`, porque el driver devuelve los enteros de 32
bits como `Integer` y `getLong` fallaría.

---

## 10. Los `[WARN]` que salen al correr

```
[WARN] DefaultDelegationTokenManager - No tokens obtained so skipping notifications
[WARN] WebMonitorUtils - Log file environment variable 'log.file' is not set.
[WARN] WebMonitorUtils - JobManager log files are unavailable in the web dashboard.
[WARN] DefaultDelegationTokenManager - Tokens update task not started...
```

No son errores. El job corre en un mini-cluster dentro de la propia JVM:

- **No hay Kerberos**, así que no hay *delegation tokens* que pedir. En un
  clúster real con Kerberos, esos avisos sí importarían.
- **No se levanta el dashboard web de Flink**, así que no existe
  `log.file` que buscar. Por eso no hay interfaz web de Flink en el
  puerto 8081: este proyecto no incluye `flink-runtime-web`.

Para subirlos o bajarlos está `src/main/resources/simplelogger.properties`,
que deja pasar solo avisos y se calla el resto del logging de Flink.

---

## 11. Resultados esperados

Si el pipeline funcionó, el job imprime exactamente esto:

| Región | Países | Población | Superficie | Grandes | País más poblado |
|---|---:|---:|---:|---:|---|
| Africa | 59 | 1.263.484.702 | 30.318.417 | 8 | Sudán (SDN) |
| Americas | 56 | 1.023.192.295 | 42.077.922 | 4 | Brasil (BRA) |
| Antarctic | 5 | 1.278.000 | 14.012.111 | 0 | Antártida (ATA) |
| Asia | 50 | 3.315.885.956 | 32.138.141 | 12 | China (CHN) |
| Europe | 53 | 1.277.756.183 | 23.022.998 | 1 | Rusia (RUS) |
| Oceania | 27 | 39.034.101 | 8.515.313 | 0 | Australia (AUS) |
| **TOTAL** | **250** | **6.920.631.237** | **150.084.902** | **25** | |

Los 250 números cuadran con los 250 documentos de `Mundo.paises`, sin
sobras ni repeticiones.

### Sobre la superficie: 150.084.902 y no 150.084.903

Si sumas las 250 superficies directamente y redondeas al final, sale
`150.084.903`. Pero Flink **redondea cada región por separado** en
`MongoResumenOutputFormat.java:70` y después suma las 6, dando
`150.084.902`.

Es aritmética por etapas, no un dato perdido. La diferencia es de 1 km²
sobre 150 millones.

### Sobre las poblaciones

Los datos de origen **no son reales**: están estimados a partir de la
superficie. Por eso Rusia aparece con 897.657.705 habitantes, India con
378.072.850 y Aruba con 3.465. Los países, capitales, fronteras, monedas e
idiomas sí son reales.

---

## 12. Resumen en una tabla

Para responder rápido en la defensa:

| Pregunta | Respuesta |
|---|---|
| ¿Dónde se aplica Flink? | `AnalisisFlinkBatch.java`, líneas 70-127 |
| ¿Dónde se conecta a la base de datos? | 7 sitios: `config.properties:8`, `MongoPaisesInputFormat.java:94` y `:155`, `MongoResumenOutputFormat.java:59`, `AnalisisFlinkBatch.java:147` y `:162`, `consultas.py:29` |
| ¿Dónde está el `map`? | `AnalisisFlinkBatch.java:97-99` |
| ¿Dónde está el `groupBy`? | `AnalisisFlinkBatch.java:108` |
| ¿Dónde está el `reduce`? | `AnalisisFlinkBatch.java:109`, cuerpo en `IndicadoresRegion.java:60` |
| ¿Dónde se escribe? | `MongoResumenOutputFormat.java:65` |
| ¿Dónde se reparte el trabajo? | `MongoPaisesInputFormat.java:88` |
| ¿Cuántas subtareas? | 4, en `config.properties:16` |
| ¿Por qué el resultado no cambia? | El reduce solo usa sumas y máximos, que conmutan |
| ¿Flink responde las búsquedas? | No. MongoDB directamente desde Python |

---

Volver a [INSTRUCCIONES.md](INSTRUCCIONES.md) para el arranque manual.
