package com.minisistema.modelo;

/**
 * La densidad de UNA region, calculada de dos maneras a proposito.
 *
 * Este es el tercer valor que viaja por un reduce de Flink, y el mas
 * interesante de los tres, porque aqui la respuesta NO es sumar.
 *
 * EL PROBLEMA DE LAS MEDIAS
 *
 * Si alguien pregunta "¿cual es la densidad de Europa?", casi todo el mundo
 * contesta "la media de las densidades de los paises". Y esa cuenta esta
 * mal, por dos motivos:
 *
 *   1. Al promediar, cada pais pesa lo mismo, sin importar su tamano. Un pais
 *      de 10 km2 pesa igual que uno de 10 millones de km2.
 *
 *   2. Al promediar, cada pais pesa por su POBLACION, no por su superficie.
 *      Con los datos reales, Europa da 55.50 hab/km2 de verdad, pero
 *      "promediando" salen 116.57: el doble, porque el resultado lo domina
 *      uno o dos paises grandes. En Antartica la diferencia es de un 5011%,
 *      porque casi todos sus paises son diminutos.
 *
 * LA DENSIDAD CORRECTA ES UN RATIO DE TOTALES
 *
 *   densidad real = poblacion total / superficie total
 *
 * Esa division se hace DESPUES del reduce, nunca durante. Por eso aqui solo
 * se suman poblaciones y superficies, que si son sumables, y la division se
 * deja para los getters.
 *
 * QUE DEMUESTRA ESTO DE FLINK
 *
* Que un reduce sirve para acumular lo que es sumable, y que hay
 * operaciones que NO se pueden trasladar al reduce porque no son
 * conmutativas. Si alguien quisiera "promediar densidades" con un reduce,
 * tendria que inventar un acumulador de tipo suma/cantidad, y el resultado
 * seria enganoso igual: estaria promediando el promedio que el propio Flink
 * acaba de calcular.
 */
public class DensidadRegion {

    private final String region;

    private long   cantidadPaises;
    private long   poblacionTotal;
    private double superficieTotal;

    /** Suma de las densidades de cada pais. NO es la densidad de la region. */
    private double sumaDensidades;

    /**
     * Cuantos paises aportan una densidad individual.
     * Existe aparte de cantidadPaises porque un pais de superficie 0 no puede
     * aportar una densidad: si se dividiera, saltaria una Infinity y el
     * promedio quedaria corrupto.
     */
    private int paisesConSuperficie;

    public DensidadRegion(String region) {
        this.region = region;
    }

    public String getRegion()            { return region; }
    public long   getCantidadPaises()    { return cantidadPaises; }
    public long   getPoblacionTotal()    { return poblacionTotal; }
    public double getSuperficieTotal()   { return superficieTotal; }
    public int    getPaisesConSuperficie(){ return paisesConSuperficie; }

    /**
     * La densidad que de verdad tiene la region: la cuenta de personas por
     * kilometro cuadrado, sin promediar nada.
     */
    public double getDensidadReal() {
        if (superficieTotal <= 0.0) {
            return 0.0;
        }
        return poblacionTotal / superficieTotal;
    }

    /**
     * La cuenta que hace casi todo el mundo: la media de las densidades
     * sueltas. Se guarda para ensenar por que esta mal.
     */
    public double getDensidadPromedio() {
        if (paisesConSuperficie == 0) {
            return 0.0;
        }
        return sumaDensidades / paisesConSuperficie;
    }

    /**
     * Cuanto se equivoca el promedio, en porcentaje respecto de la real.
     * Positivo significa que el promedio infla el resultado.
     */
    public double getErrorPorcentaje() {
        double real = getDensidadReal();
        if (real <= 0.0) {
            return 0.0;
        }
        return (getDensidadPromedio() - real) / real * 100.0;
    }

    /**
     * Crea el acumulado inicial a partir de un pais.
     * Lo invoca el map() de Flink, una vez por cada uno de los 250.
     */
    public static DensidadRegion desdePais(Pais pais) {
        DensidadRegion d = new DensidadRegion(pais.getRegion());
        d.agregar(pais);
        return d;
    }

    /**
     * Fusiona este acumulado con el de otra region identica.
     * Lo invoca el reduce() de Flink.
     *
     * Todas las sumas conmutan, asi que el resultado no depende del orden.
     * Aqui no hay ningun max, ni ninguna lista: solo sumas de numeros, que es
     * lo mas seguro que puede llegar por la red.
     */
    public DensidadRegion sumar(DensidadRegion otro) {
        this.cantidadPaises      += otro.cantidadPaises;
        this.poblacionTotal      += otro.poblacionTotal;
        this.superficieTotal     += otro.superficieTotal;
        this.sumaDensidades      += otro.sumaDensidades;
        this.paisesConSuperficie += otro.paisesConSuperficie;
        return this;
    }

    /** Suma un pais a este acumulado. */
    private void agregar(Pais pais) {
        this.cantidadPaises++;
        this.poblacionTotal      += pais.getPoblacion();
        this.superficieTotal     += pais.getSuperficieKm2();

        // Un pais sin superficie no puede tener densidad. Se cuenta aparte
        // para que el promedio no se vaya por las ramas.
        if (pais.getSuperficieKm2() > 0.0) {
            this.sumaDensidades      += pais.getPoblacion() / pais.getSuperficieKm2();
            this.paisesConSuperficie++;
        }
    }

    @Override
    public String toString() {
        return region + ": real " + getDensidadReal()
             + " hab/km2 (promedio " + getDensidadPromedio() + ")";
    }
}