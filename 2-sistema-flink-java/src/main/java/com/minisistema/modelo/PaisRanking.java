package com.minisistema.modelo;

import java.io.Serializable;

/**
 * Una fila del ranking: como queda un pais dentro del Top 5 de su region.
 *
 * Es una clase aparte, y no se reutiliza Pais, porque el Top-N necesita
 * guardar los datos ya ordenados y recortados. Si se guardara el Pais entero,
 * cada subtarea arrastraria los 250 paises por la red solo para descartar
 * 245 de ellos.
 *
 * Implements Serializable porque viaja dentro de una List de TopPaisesRegion,
 * y esa lista tiene que poder mandarse de una subtarea a otra por la red.
 */
public class PaisRanking implements Serializable, Comparable<PaisRanking> {

    private static final long serialVersionUID = 1L;

    private final String codigo;
    private final String nombre;
    private final long   poblacion;
    private final double superficieKm2;

    public PaisRanking(String codigo, String nombre, long poblacion, double superficieKm2) {
        this.codigo       = codigo;
        this.nombre       = nombre;
        this.poblacion    = poblacion;
        this.superficieKm2 = superficieKm2;
    }

    public String getCodigo()       { return codigo; }
    public String getNombre()       { return nombre; }
    public long   getPoblacion()    { return poblacion; }
    public double getSuperficieKm2(){ return superficieKm2; }

    /** Crea la fila a partir de un pais ya leido de MongoDB. */
    public static PaisRanking desdePais(Pais pais) {
        return new PaisRanking(
                pais.getCodigo(),
                pais.getNombre(),
                pais.getPoblacion(),
                pais.getSuperficieKm2());
    }

    /**
     * ORDEN DEL RANKING: mas poblacion primero, y a igualdad el codigo mas
     * pequeno primero.
     *
     * El desempate por codigo NO es decorativo. Si dos paises de la misma
     * region tuvieran exactamente la misma poblacion, y se ordenara solo por
     * poblacion, el ganador lo decidiria el orden de llegada de los datos: eso
     * es decir, dependeria de como Flink repartiera las subtareas, y el
     * resultado cambiaria de una corrida a otra.
     *
     * Como el codigo ISO es unico, desempatar por el hace que la lista sea
     * conmutativa de verdad: da igual en que orden se fusionen los
     * acumulados, el resultado sale siempre igual.
     */
    @Override
    public int compareTo(PaisRanking otro) {
        // Se comparan las poblaciones de mayor a menor.
        int porPoblacion = Long.compare(otro.poblacion, this.poblacion);
        if (porPoblacion != 0) {
            return porPoblacion;
        }
        // Empate: el codigo mas pequeno gana, para que sea siempre igual.
        return this.codigo.compareTo(otro.codigo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaisRanking otro)) {
            return false;
        }
        return codigo.equals(otro.codigo);
    }

    @Override
    public int hashCode() {
        return codigo.hashCode();
    }

    @Override
    public String toString() {
        return codigo + " " + nombre + " (" + poblacion + ")";
    }
}