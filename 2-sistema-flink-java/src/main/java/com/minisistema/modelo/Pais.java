package com.minisistema.modelo;

import org.bson.Document;

/**
 * Un pais, tal y como viene de MongoDB.
 *
 * Solo guarda los campos que el job necesita. Los 250 documentos de la
 * coleccion tienen 12 campos, pero aqui se leen 5. Los otros 7 los usa
 * directamente la interfaz web, no el calculo.
 *
 * IMPORTANTE: los campos numericos NO se leen con un cast directo.
 *
 * En la base de datos los tipos BSON estan mezclados:
 *
 *   poblacion       int    en los 250
 *   superficie_km2  int    en 247 paises, double en 3
 *   densidad        int    en 70 paises,  double en 180
 *
 * Si se hiciera  (int) documento.get("superficie_km2")  fallaria con
 * ClassCastException en esos 3 paises de superficie decimal.
 * Por eso todo se lee con metodos estaticos que toleran ambos tipos.
 */
public class Pais {

    private String codigo;       // _id, 3 letras: RUS, ABW, CHL
    private String nombre;       // nombre_comun
    private String region;
    private long   poblacion;
    private double superficieKm2;

    public Pais(String codigo, String nombre, String region,
                long poblacion, double superficieKm2) {
        this.codigo        = codigo;
        this.nombre        = nombre;
        this.region        = region;
        this.poblacion     = poblacion;
        this.superficieKm2 = superficieKm2;
    }

    public String getCodigo()      { return codigo; }
    public String getNombre()      { return nombre; }
    public String getRegion()      { return region; }
    public long   getPoblacion()   { return poblacion; }
    public double getSuperficieKm2(){ return superficieKm2; }

    /**
     * Convierte un documento de MongoDB en un Pais.
     */
    public static Pais desde(Document d) {
        return new Pais(
            texto(d, "_id", "SIN-CODIGO"),
            texto(d, "nombre_comun", "Sin nombre"),
            texto(d, "region", "Sin region"),
            numeroLargo(d, "poblacion"),
            numeroDecimal(d, "superficie_km2")
        );
    }

    /**
     * Igual que desde(), pero escribiendo sobre un Pais que ya existe.
     *
     * Flink pasa un objeto a reciclar en cada nextRecord(), y asi el job
     * no crea 250 objetos nuevos sino 4, uno por subtarea.
     */
    public static Pais copiarEn(Pais destino, Document d) {
        destino.codigo        = texto(d, "_id", "SIN-CODIGO");
        destino.nombre        = texto(d, "nombre_comun", "Sin nombre");
        destino.region        = texto(d, "region", "Sin region");
        destino.poblacion     = numeroLargo(d, "poblacion");
        destino.superficieKm2 = numeroDecimal(d, "superficie_km2");
        return destino;
    }

    // -----------------------------------------------------------------------
    //  Lectura tolerante de los tipos de MongoDB
    // -----------------------------------------------------------------------

    /** Lee un campo de texto. */
    private static String texto(Document d, String campo, String porDefecto) {
        Object valor = d.get(campo);
        return (valor == null) ? porDefecto : String.valueOf(valor);
    }

    /**
     * Lee un numero que puede venir como int o como double.
     * Devuelve 0.0 si el campo no existe o esta vacio.
     */
    private static double numeroDecimal(Document d, String campo) {
        Object valor = d.get(campo);
        if (valor == null) {
            return 0.0;
        }
        if (valor instanceof Number numero) {
            return numero.doubleValue();
        }
        // Si alguien guardo el numero como texto.
        try {
            return Double.parseDouble(String.valueOf(valor));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Idem, pero devolviendo long, para contar personas. */
    private static long numeroLargo(Document d, String campo) {
        Object valor = d.get(campo);
        if (valor == null) {
            return 0L;
        }
        if (valor instanceof Number numero) {
            return numero.longValue();
        }
        try {
            return (long) Double.parseDouble(String.valueOf(valor));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return codigo + " " + nombre + " (" + region + ")";
    }
}
