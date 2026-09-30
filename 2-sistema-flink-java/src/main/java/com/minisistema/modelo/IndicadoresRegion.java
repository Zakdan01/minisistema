package com.minisistema.modelo;

/**
 * Los totales acumulados de UNA region.
 *
 * Esta clase es el valor que viaja por el reduce de Flink. Cada vez que
 * dos subtareas intercambian acumulados, sus objetos se fusionan aqui
 * dentro de sumar(), y el resultado vuelve al circulo. Al final hay un
 * solo objeto por region con las cuatro cifras sumadas.
 *
 * El orden de las operaciones depende de la red, no del orden del
 * documento: el mismo pais puede terminar fusionandose de otra manera
 * en cada ejecucion. Por eso sumar() esta hecho con operaciones conmutativas
 * (sumar, sumar max, sumar min, sumar true/false), que dan el mismo
 * resultado en cualquier orden.
 */
public class IndicadoresRegion {

    private final String region;
    private long   cantidadPaises;
    private long   poblacionTotal;
    private double superficieTotal;   // double: al sumar areas, los 3 paises
                                      // de superficie decimal se pierden si
                                      // se usara long
    private long   poblacionMaxima;
    private String paisMasPoblado;
    private long   cantidadGrandes;   // paises por encima del umbral

    public IndicadoresRegion(String region) {
        this.region = region;
    }

    // --- getters para el sumidero y la consola ---

    public String getRegion()          { return region; }
    public long   getCantidadPaises()  { return cantidadPaises; }
    public long   getPoblacionTotal()  { return poblacionTotal; }
    public double getSuperficieTotal() { return superficieTotal; }
    public long   getPoblacionMaxima() { return poblacionMaxima; }
    public String getPaisMasPoblado()  { return paisMasPoblado; }
    public long   getCantidadGrandes() { return cantidadGrandes; }

    /**
     * Crea el acumulado inicial a partir de un pais.
     * Lo invoca el map() de Flink, una vez por cada uno de los 250.
     */
    public static IndicadoresRegion desdePais(Pais pais, long umbral) {
        IndicadoresRegion i = new IndicadoresRegion(pais.getRegion());
        i.agregar(pais, umbral);
        return i;
    }

    /**
     * Fusiona este acumulado con los de otra region identica.
     * Lo invoca el reduce() de Flink.
     *
     * Todas las operaciones conmutan, asi que el resultado no depende
     * del orden en que lleguen los acumulados.
     */
    public IndicadoresRegion sumar(IndicadoresRegion otro) {
        this.cantidadPaises += otro.cantidadPaises;
        this.poblacionTotal += otro.poblacionTotal;
        this.superficieTotal += otro.superficieTotal;
        this.cantidadGrandes += otro.cantidadGrandes;

        if (otro.poblacionMaxima > this.poblacionMaxima) {
            this.poblacionMaxima = otro.poblacionMaxima;
            this.paisMasPoblado   = otro.paisMasPoblado;
        }
        return this;
    }

    /** Suma un pais a este acumulado. */
    private void agregar(Pais pais, long umbral) {
        this.cantidadPaises += 1;
        this.poblacionTotal += pais.getPoblacion();
        this.superficieTotal += pais.getSuperficieKm2();

        if (pais.getPoblacion() > umbral) {
            this.cantidadGrandes += 1;
        }

        if (pais.getPoblacion() > this.poblacionMaxima) {
            this.poblacionMaxima = pais.getPoblacion();
            this.paisMasPoblado   = pais.getNombre() + " (" + pais.getCodigo() + ")";
        }
    }

    @Override
    public String toString() {
        return region + ": " + cantidadPaises + " paises, "
             + poblacionTotal + " hab, " + Math.round(superficieTotal) + " km2";
    }
}
