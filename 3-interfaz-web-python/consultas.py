"""
Consultas a MongoDB para la interfaz web.

Este modulo solo LEE. Nunca escribe: los paises son la fuente y el
resumen por region ya lo calculo el job de Apache Flink.

Las 3 operaciones de la interfaz se resuelven aqui:

  1. Ver las fichas       -> listar_paises()
  2. Comparar poblaciones -> comparar_paises()
  3. Buscar               -> buscar_paises()
"""

from pymongo import MongoClient
from pymongo.errors import PyMongoError

# --- Conexion ---------------------------------------------------------------

# Un solo cliente para toda la aplicacion. PyMongo mantiene un pool de
# conexiones dentro, asi que todas las peticiones lo reusan.
# Es lo contrario del job de Flink, donde cada subtarea abre la suya.
_cliente = None
_base = None


def conectar(uri="mongodb://localhost:27017", base="Mundo"):
    """Abre la conexion. Se llama una vez al arrancar el servidor."""
    global _cliente, _base
    _cliente = MongoClient(uri, serverSelectionTimeoutMS=5000)
    _base = _cliente[base]
    return _cliente


def db():
    if _cliente is None:
        conectar()
    return _base


def collection(nombre):
    return db()[nombre]


# --- 1. FICHAS --------------------------------------------------------------

def listar_paises(limite=None, region=None):
    """
    Devuelve los paises para las fichas.

    limite = None trae los 250 de golpe. Es poco para MongoDB, y el
    navegador los muestra de una vez sin esperas.
    """
    consulta = {}
    if region:
        consulta["region"] = region

    documentos = collection("paises").find(consulta)
    if limite:
        documentos = documentos.limit(limite)

    return [_pais_a_dict(d) for d in documentos]


def _pais_a_dict(d):
    """Convierte un documento de MongoDB en algo que el navegador entienda.

    MongoDB guarda las monedas como una lista de objetos {codigo, nombre,
    simbolo}, y los campos numericos con tipos mezclados (int y double).
    Aqui se normaliza todo a tipos que JSON sepa manejar.
    """
    return {
        "codigo":       d.get("_id", ""),
        "nombre":       d.get("nombre_comun", ""),
        "nombre_oficial": d.get("nombre_oficial", ""),
        "region":       d.get("region", ""),
        "subregion":    d.get("subregion", ""),
        "capital":      d.get("capital", []),
        "poblacion":    int(d.get("poblacion", 0) or 0),
        "superficie":   float(d.get("superficie_km2", 0) or 0),
        "densidad":     float(d.get("densidad", 0) or 0),
        "monedas":      d.get("monedas", []),
        "idiomas":      d.get("idiomas", []),
        "fronteras":    d.get("fronteras", []),
    }


# --- 2. COMPARAR POBLACIONES ------------------------------------------------

def comparar_paises(codigos):
    """
    Compara la poblacion de los paises indicados.

    Devuelve cada pais con su porcentaje sobre el total de la comparacion
    y sobre la poblacion mundial, para que la cifra tenga contexto.
    """
    if not codigos:
        return {"paises": [], "total": 0, "mayor": None, "menor": None,
                "poblacionMundial": 0}

    documentos = list(collection("paises").find({"_id": {"$in": list(codigos)}}))
    paises = [_pais_a_dict(d) for d in documentos]

    # Se ordena por el orden que pidio el navegador, no por el de MongoDB,
    # para que las barras salgan en el mismo orden que los checkbox.
    orden = {c: i for i, c in enumerate(codigos)}
    paises.sort(key=lambda p: orden.get(p["codigo"], 9999))

    total = sum(p["poblacion"] for p in paises)
    mundial = resumen_mundial()["poblacion_total"]

    mayor = max(paises, key=lambda p: p["poblacion"]) if paises else None
    menor = min(paises, key=lambda p: p["poblacion"]) if paises else None

    for p in paises:
        p["porcentajeComparacion"] = (p["poblacion"] / total * 100) if total else 0
        p["porcentajeMundial"] = (p["poblacion"] / mundial * 100) if mundial else 0

    return {
        "paises": paises,
        "total": total,
        "mayor": mayor["codigo"] if mayor else None,
        "menor": menor["codigo"] if menor else None,
        "razon": (mayor["poblacion"] / menor["poblacion"]) if (mayor and menor and menor["poblacion"]) else 0,
        "poblacionMundial": mundial,
    }


# --- 3. BUSCADOR ------------------------------------------------------------

def buscar_paises(texto="", region=None, moneda=None, idioma=None,
                  superficieMin=None, superficieMax=None,
                  poblacionMin=None, poblacionMax=None):
    """
    Busca paises con todos los filtros que se le pasen a la vez.

    Cada filtro es opcional y se combinan con AND: si buscas moneda "euro"
    y superficie mayor a 500.000, sale solo lo que cumple las dos cosas.

    Se delega en MongoDB con $regex e $gte/$lt en vez de filtrar en Python,
    para que el trabajo se haga en el motor de la base de datos.
    """
    consulta = {}

    if texto:
        # $regex busca dentro del nombre, la capital y la region a la vez.
        # 'i' es para que no importe si va en mayuscula o minuscula.
        patron = {"$regex": texto.strip(), "$options": "i"}
        consulta["$or"] = [
            {"nombre_comun": patron},
            {"nombre_oficial": patron},
            {"capital": patron},
            {"region": patron},
            {"subregion": patron},
        ]

    if region:
        consulta["region"] = region

    if moneda:
        # Las monedas son objetos {codigo, nombre, simbolo}, asi que
        # se busca dentro de monedas.nombre.
        consulta["monedas.nombre"] = {"$regex": moneda.strip(), "$options": "i"}

    if idioma:
        # Los idiomas son texto plano en una lista, no objetos.
        consulta["idiomas"] = {"$regex": idioma.strip(), "$options": "i"}

    # Los rangos numéricos van en el mismo campo con $gte y $lte.
    if superficieMin is not None or superficieMax is not None:
        rango = {}
        if superficieMin is not None:
            rango["$gte"] = float(superficieMin)
        if superficieMax is not None:
            rango["$lte"] = float(superficieMax)
        consulta["superficie_km2"] = rango

    if poblacionMin is not None or poblacionMax is not None:
        rango = {}
        if poblacionMin is not None:
            rango["$gte"] = int(poblacionMin)
        if poblacionMax is not None:
            rango["$lte"] = int(poblacionMax)
        consulta["poblacion"] = rango

    documentos = collection("paises").find(consulta)
    paises = [_pais_a_dict(d) for d in documentos]
    paises.sort(key=lambda p: -p["poblacion"])
    return paises


# --- DATOS PARA LOS DESPLEGABLES DEL BUSCADOR -------------------------------

def opciones_filtro():
    """
    Llena los desplegables de la pagina con lo que hay de verdad en la base.

    No se escribe ninguna lista a mano: si manana se agrega un pais con una
    moneda nueva, el desplegable la muestra solo.
    """
    monedas = set()
    for d in collection("paises").find({}, {"monedas": 1}):
        for m in d.get("monedas", []):
            nombre = m.get("nombre")
            if nombre:
                monedas.add(nombre)

    idiomas = set()
    for d in collection("paises").find({}, {"idiomas": 1}):
        for i in d.get("idiomas", []):
            idiomas.add(i)

    regiones = [r for r in collection("paises").distinct("region") if r]

    return {
        "monedas": sorted(monedas),
        "idiomas": sorted(idiomas),
        "regiones": sorted(regiones),
        "totalPaises": collection("paises").count_documents({}),
    }


# --- RESULTADOS DEL JOB DE FLINK --------------------------------------------

def obtener_resumen():
    """
    Lee los 6 resultados que escribio el job de Apache Flink.

    Si la coleccion esta vacia, significa que el job todavia no se ha
    corrido, y la pagina avisa de eso en vez de fallar.
    """
    documentos = list(collection("resumen_regiones").find().sort("region", 1))
    regiones = [
        {
            "region":         d.get("region", ""),
            "cantidad":       int(d.get("cantidad_paises", 0) or 0),
            "poblacion":      int(d.get("poblacion_total", 0) or 0),
            "superficie":     int(d.get("superficie_total", 0) or 0),
            "poblacionMaxima": int(d.get("poblacion_maxima", 0) or 0),
            "paisMasPoblado": d.get("pais_mas_poblado", ""),
            "grandes":        int(d.get("cantidad_grandes", 0) or 0),
        }
        for d in documentos
    ]
    return regiones


def resumen_mundial():
    """Suma los 6 resultados de Flink para tener los totales generales."""
    regiones = obtener_resumen()
    return {
        "paises":         sum(r["cantidad"] for r in regiones),
        "poblacion_total": sum(r["poblacion"] for r in regiones),
        "superficie":     sum(r["superficie"] for r in regiones),
        "grandes":        sum(r["grandes"] for r in regiones),
    }


# --- ESTADO, para el pie de la pagina ---------------------------------------

def estado():
    """
    Dice si el sistema esta listo y de donde salen los numeros.
    La pagina usa esto para no mostrar datos viejos sin avisar.
    """
    try:
        total_paises = collection("paises").count_documents({})
        regiones = obtener_resumen()
        return {
            "mongo": True,
            "paises": total_paises,
            "regiones": len(regiones),
            "jobCorrido": len(regiones) > 0,
            "servidor": _cliente.server_info().get("version", "?"),
        }
    except PyMongoError as e:
        return {"mongo": False, "error": str(e), "jobCorrido": False}
