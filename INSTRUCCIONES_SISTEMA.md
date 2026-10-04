# INSTRUCCIONES DEL SISTEMA - Mondo (Apache Flink + MongoDB + Python Web)

Este manual detalla paso a paso los requisitos previos, el proceso de instalación, el funcionamiento interno de los scripts de control y la resolución de problemas comunes del sistema.

---

## 0. Prerrequisitos previos: Configuración de la Base de Datos (MongoDB)

Antes de ejecutar cualquier script, el sistema requiere que la base de datos esté creada y poblada en MongoDB.

* **Servidor MongoDB**: Debe estar activo y accesible en tu equipo (por defecto en `mongodb://localhost:27017`).
* **Creación de la base de datos y carga de datos**:
  1. Abre tu gestor o consola de MongoDB.
  2. Crea la base de datos con el nombre **`Mundo`**.
  3. Importa los datos iniciales utilizando el archivo **`paises_mongo.json`** ubicado en la raíz del proyecto hacia la colección **`paises`**.
  4. **Ejemplo mediante consola (`mongoimport`)**:
     ```cmd
     mongoimport --db Mundo --collection paises --file paises_mongo.json --jsonArray
     ```
* **Aclaración sobre las colecciones**:
  * La colección `Mundo.paises` es de **solo lectura**. El job por lotes de Apache Flink leerá de ella pero nunca la modificará.
  * El job escribirá sus resultados calculados en tres colecciones independientes: `resumen_regiones`, `top_paises_region` y `densidad_regiones`.

---

## 1. Paso 1: Ejecutar `instalar.bat` (Instalación Única)

### ¿Qué es `instalar.bat`?
Es el script lanzador encargado de preparar la máquina. **Se ejecuta una única vez** en un equipo nuevo (aunque es totalmente seguro volver a correrlo, ya que cada subtarea detecta si ya está completada y la omite).

### Detalle de subtareas que realiza bajo el capó (`instalar.ps1`):

#### A. Detección y enlace con PowerShell
* Busca automáticamente la ruta del ejecutable de PowerShell en el sistema operativo Windows (compatible con Windows PowerShell 5.1 y PowerShell Core).
* Configura políticas de ejecución locales de forma segura (`-ExecutionPolicy Bypass`) para evitar bloqueos por políticas de seguridad del sistema.

#### B. Verificación e Instalación de Java / JDK 21
* **Búsqueda de JDK**: Comprueba en orden de prioridad:
  1. La variable de entorno `JAVA_HOME`.
  2. Una instalación previa en la carpeta local del proyecto `.herramientas\jdk\`.
  3. Instalaciones del sistema (Program Files / Adoptium / Microsoft / etc.).
* **Validación de versión**: Comprueba que la versión de Java encontrada sea al menos la **versión 17** (se recomienda JDK 21).
* **Descarga automática**: Si no encuentra un JDK válido, descarga automáticamente la última versión LTS de **OpenJDK 21 (HotSpot para x64)** desde la API oficial de Adoptium, descomprimiéndola de forma aislada dentro de `.herramientas\jdk\` (unos 196 MB). Esto garantiza que no afecte ni dependa de las variables globales de Java del sistema operativo.

#### C. Creación del Entorno Virtual de Python y Dependencias
* Localiza la instalación de Python en la máquina.
* Crea un entorno virtual aislado (`.venv`) dentro de la carpeta del servidor web (`3-interfaz-web-python\.venv\`).
* Actualiza `pip` e instala automáticamente las librerías necesarias especificadas en el proyecto, destacando **`pymongo`** para la conexión con la base de datos.

#### D. Descarga de Dependencias de Apache Flink (Maven)
* Verifica y descarga mediante Maven (`mvn`) las dependencias y librerías JAR necesarias para el funcionamiento del job de Apache Flink en `2-sistema-flink-java\target\lib\`.

### ¿Cómo se ejecuta?
Haz doble clic sobre el archivo **`instalar.bat`** o ejecútalo en tu consola:
```cmd
instalar.bat
```
*(Requiere conexión a internet la primera vez para descargar el JDK y los paquetes de Python).*

---

## 2. Paso 2: Ejecutar `arrancar.bat` (Lanzador del Sistema)

### ¿Qué es `arrancar.bat`?
Es el script encargado de poner en marcha el procesamiento de datos y la interfaz web. A diferencia de `instalar.bat`, **no instala nada** y se puede ejecutar todas las veces que desees.

### Detalle de subtareas que realiza bajo el capó (`arrancar.ps1`):

#### A. Comprobación del Estado de la Base de Datos
* Realiza una consulta de solo lectura a MongoDB para verificar que el servidor responde en `localhost:27017` y que la base de datos `Mundo` contiene los 250 países cargados correctamente.
* Si detecta algún problema (como MongoDB apagado), emite una advertencia descriptiva sin interrumpir el flujo del script.

#### B. Ejecución del Job por Lotes de Apache Flink
* Compila y ejecuta el pipeline de procesamiento en Java (`AnalisisFlinkBatch.java`) utilizando la API de Flink DataSet (paralelismo 4).
* **Fases del procesamiento**:
  1. Lee los 250 documentos de `Mundo.paises`.
  2. Ejecuta tres ramas de cálculo simultáneas:
     * **Resumen de Regiones**: Agrupa por región sumando población, superficie, región máxima, país más poblado y países > 50M.
     * **Top 5 por Región**: Ordena por población descendente y código ISO ascendente (desempate determinista), recortando los 5 primeros puestos.
     * **Densidad Real vs Promedio Ingenuo**: Calcula la densidad real regional y la contrasta con la media aritmética de densidades por país, calculando el porcentaje de error con signo.
  3. Vierte los resultados limpios en MongoDB (`resumen_regiones`, `top_paises_region`, `densidad_regiones`), borrando previamente registros anteriores para evitar duplicados.

#### C. Arranque del Servidor Web Python
* Cierra de forma limpia cualquier instancia previa colgada del servidor web local para evitar conflictos en el puerto.
* Inicia el servidor HTTP de Python (`servidor.py`) en segundo plano escuchando en el puerto **`8000`**.
* Habilita los endpoints REST para la interfaz web (`/api/resumen`, `/api/top`, `/api/densidades`, `/api/paises`, `/api/estado`, etc.).
* Abre automáticamente (o deja listo para consultar) la aplicación web en tu navegador predeterminado en:
  `http://127.0.0.1:8000/`

---

## 3. Opciones de Ejecución del Lanzador

El script `arrancar.bat` acepta parámetros adicionales para controlar qué servicios levantar:

1. **Ejecución Completa (Job de Flink + Servidor Web)**:
   ```cmd
   arrancar.bat
   ```
   *(Ejecuta el procesamiento en Flink y acto seguido levanta la web).*

2. **Solo Interfaz Web (`solo-web`)**:
   ```cmd
   arrancar.bat solo-web
   ```
   *(Útil si ya ejecutaste el job de Flink y solo deseas navegar por la web o realizar pruebas en la interfaz sin volver a procesar los datos en Java).*

3. **Solo Job de Flink (`solo-job`)**:
   ```cmd
   arrancar.bat solo-job
   ```
   *(Ejecuta exclusivamente el procesamiento por lotes en Apache Flink y actualiza las colecciones en MongoDB sin levantar el servidor web).*

---

## 4. Estructura de Datos Generada por Flink en MongoDB

Una vez ejecutado el job, Apache Flink escribe los resultados agregados en la base de datos `Mundo` con la siguiente estructura:

1. **`Mundo.resumen_regiones`**: 1 documento por región con indicadores agregados (población total, superficie total, país más poblado, cantidad de países y conteo de países con más de 50 millones de habitantes).
2. **`Mundo.top_paises_region`**: 1 documento por región con un array que contiene los 5 países más poblados ordenados correctamente (`puesto`, `codigo`, `nombre`, `poblacion`, `superficie`).
3. **`Mundo.densidad_regiones`**: 1 documento por región que almacena la densidad real (`densidadReal`), la densidad promedio de los países (`densidadPromedio`) y el porcentaje de error (`error`).

---

## 5. Enlaces y Navegación Directa por Hash en la Web

La interfaz web soporta navegación instantánea y acceso directo mediante `#hash` en la URL para compartir vistas específicas:
* `http://127.0.0.1:8000/#fichas` — Fichas de países con filtros interactivos.
* `http://127.0.0.1:8000/#comparar` — Comparador gráfico de poblaciones.
* `http://127.0.0.1:8000/#buscar` — Buscador avanzado multicriterio.
* `http://127.0.0.1:8000/#resumen` — Resumen por región calculado por Flink.
* `http://127.0.0.1:8000/#top` — Top 5 países más poblados por región en tarjetas Bootstrap.
* `http://127.0.0.1:8000/#densidades` — Comparativa de densidad real vs. promedio ingenuo y métricas de error.

---

## 6. Resolución de Problemas (Troubleshooting)

* **MongoDB no responde**: Asegúrate de que el servicio esté iniciado en tu máquina. En Windows puedes verificarlo ejecutando `net start MongoDB` en una terminal con privilegios de administrador.
* **Problemas de conectividad al instalar por primera vez (`instalar.bat`)**: Si no dispones de conexión a internet para descargar el JDK de Adoptium, puedes colocar manualmente una distribución de JDK 17 o 21 descomprimida dentro de la carpeta `.herramientas\jdk\`.
* **Puerto 8000 ocupado**: El script `arrancar.bat` detecta y libera automáticamente instancias colgadas de `servidor.py` antes de iniciar el servidor web. Si persiste, puedes cerrar los procesos de Python desde el Administrador de tareas.
