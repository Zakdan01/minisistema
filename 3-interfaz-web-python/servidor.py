"""
Servidor web de la interfaz.

Usa http.server de la libreria estandar de Python, sin instalar nada mas.
Flask seria mas comodo para escribir, pero aqui no hace falta: la interfaz
son 6 rutas y una carpeta de archivos estaticos.

Las rutas:

  GET /                     la pagina
  GET /api/estado           si MongoDB responde y si el job ya corrio
  GET /api/paises           las 250 fichas
  GET /api/buscar           el buscador, con filtros por query
  POST /api/comparar        comparar la poblacion de los paises elegidos
  GET /api/resumen          los 6 resultados que calculo Apache Flink
  GET /api/opciones         lo que hay en los desplegables del buscador
"""

import json
import os
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import consultas

PUERTO = 8000
CARPETA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

# Tipos de cada extension. Sin esto el navegador no sabe como interpretar
# un .css y lo descarga en vez de aplicarlo.
TIPOS = {
    ".html": "text/html; charset=utf-8",
    ".css":  "text/css; charset=utf-8",
    ".js":   "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg":  "image/svg+xml",
    ".ico":  "image/x-icon",
}


class Manejador(BaseHTTPRequestHandler):

    # -----------------------------------------------------------------------
    #  Responder
    # -----------------------------------------------------------------------

    def _responder(self, codigo, cuerpo, tipo="application/json; charset=utf-8"):
        if isinstance(cuerpo, (dict, list)):
            cuerpo = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
        elif isinstance(cuerpo, str):
            cuerpo = cuerpo.encode("utf-8")

        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        self.wfile.write(cuerpo)

    def _error(self, codigo, mensaje):
        self._responder(codigo, {"error": mensaje})

    def _leer_json(self):
        largo = int(self.headers.get("Content-Length", 0))
        if largo == 0:
            return {}
        crudo = self.rfile.read(largo).decode("utf-8")
        try:
            return json.loads(crudo)
        except json.JSONDecodeError:
            return {}

    # -----------------------------------------------------------------------
    #  Archivos estaticos: el HTML, el CSS y el JS
    # -----------------------------------------------------------------------

    def _servir_estatico(self, nombre):
        # No se permite salir de la carpeta static con ..
        seguro = os.path.normpath(nombre).lstrip("\\/")
        if seguro.startswith("..") or os.path.isabs(seguro):
            return self._error(403, "Ruta no permitida")

        ruta = os.path.join(CARPETA, seguro.replace("\\", "/"))
        if not os.path.isfile(ruta):
            return self._error(404, f"No existe {seguro}")

        extension = os.path.splitext(ruta)[1].lower()
        with open(ruta, "rb") as f:
            contenido = f.read()
        self._responder(200, contenido, TIPOS.get(extension, "application/octet-stream"))

    # -----------------------------------------------------------------------
    #  GET
    # -----------------------------------------------------------------------

    def do_GET(self):
        partes = urllib.parse.urlparse(self.path)
        ruta = partes.path
        argumentos = urllib.parse.parse_qs(partes.query)

        try:
            if ruta == "/":
                return self._servir_estatico("index.html")

            if ruta == "/api/estado":
                return self._responder(200, consultas.estado())

            if ruta == "/api/paises":
                limite = argumentos.get("limite", [None])[0]
                region = argumentos.get("region", [None])[0]
                paises = consultas.listar_paises(
                    limite=int(limite) if limite else None,
                    region=region if region else None,
                )
                return self._responder(200, {"paises": paises, "total": len(paises)})

            if ruta == "/api/resumen":
                regiones = consultas.obtener_resumen()
                return self._responder(200, {
                    "regiones": regiones,
                    "mundial": consultas.resumen_mundial(),
                })

            if ruta == "/api/opciones":
                return self._responder(200, consultas.opciones_filtro())

            if ruta == "/api/buscar":
                return self._responder(200, {"paises": self._buscar(argumentos)})

            if not ruta.startswith("/api/"):
                return self._servir_estatico(ruta.lstrip("/"))

            return self._error(404, f"Ruta no encontrada: {ruta}")

        except Exception as e:                      # noqa: BLE001
            return self._error(500, f"{type(e).__name__}: {e}")

    def _buscar(self, argumentos):
        """Lee los filtros del buscador, que llegan en la URL."""
        def numero(nombre):
            valor = argumentos.get(nombre, [None])[0]
            if valor is None or valor == "":
                return None
            try:
                return float(valor)
            except ValueError:
                return None

        def entero(nombre):
            valor = numero(nombre)
            return int(valor) if valor is not None else None

        def texto(nombre):
            valor = argumentos.get(nombre, [""])[0].strip()
            return valor or None

        return consultas.buscar_paises(
            texto=texto("q"),
            region=texto("region"),
            moneda=texto("moneda"),
            idioma=texto("idioma"),
            superficieMin=numero("superficieMin"),
            superficieMax=numero("superficieMax"),
            poblacionMin=entero("poblacionMin"),
            poblacionMax=entero("poblacionMax"),
        )

    # -----------------------------------------------------------------------
    #  POST
    # -----------------------------------------------------------------------

    def do_POST(self):
        partes = urllib.parse.urlparse(self.path)

        try:
            if partes.path == "/api/comparar":
                datos = self._leer_json()
                codigos = datos.get("codigos", [])

                if not isinstance(codigos, list):
                    return self._error(400, "Se esperaba una lista de codigos")
                if len(codigos) > 40:
                    return self._error(400, "Maximo 40 paises por comparacion")

                return self._responder(200, consultas.comparar_paises(codigos))

            return self._error(404, f"Ruta no encontrada: {partes.path}")

        except Exception as e:                      # noqa: BLE001
            return self._error(500, f"{type(e).__name__}: {e}")

    # -----------------------------------------------------------------------
    #  Para no ensuciar la consola con cada peticion
    # -----------------------------------------------------------------------

    def log_message(self, formato, *args):
        pass


def main():
    info = consultas.estado()

    print()
    print("=" * 58)
    print("  INTERFAZ WEB - MONDO")
    print("=" * 58)

    if not info.get("mongo"):
        print("  [X] MongoDB no responde en localhost:27017")
        print("      El servidor sigue arrancando para que puedas ver el error.")
        print(f"      Detalle: {info.get('error')}")
    else:
        print(f"  [OK] MongoDB {info['servidor']} en localhost:27017")
        print(f"       paises en la base: {info['paises']}")
        if info["jobCorrido"]:
            print(f"  [OK] Job de Flink ya corrio: hay {info['regiones']} regiones")
        else:
            print("  [!] El job de Flink todavia no ha corrido.")
            print("      Corre  2-sistema-flink-java\\run.bat  para tener los resumenes.")

    print()
    print(f"  Abre en el navegador:  http://localhost:{PUERTO}")
    print()
    print("  Para detener el servidor: Ctrl+C")
    print("=" * 58)
    print()

    servidor = ThreadingHTTPServer(("localhost", PUERTO), Manejador)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\n  Servidor detenido.")
        servidor.shutdown()


if __name__ == "__main__":
    main()
