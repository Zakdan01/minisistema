package com.minisistema.flink;

import org.apache.flink.core.io.InputSplit;

/**
 * Un trozo de la coleccion de paises.
 *
 * Flink parte el trabajo en InputSplit. Un split por subtarea, y cada uno
 * dice que intervalo de documentos le toca procesar.
 *
 * Aqui se reparte por la letra inicial del codigo de pais, que va de A a Z
 * y es un reparto estable y que no cambia entre ejecuciones. Con 250
 * paises y 4 subtareas el reparto es practicamente 62-63 por lado.
 *
 * Un detalle importante: los limites NO se calculan con
 *
 *     (long) (total * indice / subtareas)
 *
 * porque total * indice con enteros se trunca y los ultimos paises se
 * pierden. Se usa
 *
 *     limite = (long) Math.floor((double) total * indice / subtareas)
 *
 * que hace la division en decimal y despues redondea hacia abajo.
 * Los splits siempre quedan pegados: uno termina donde empieza el otro.
 *
 * Este objeto viaja del cliente al servidor de Flink, asi que tiene que
 * ser Serializable. Por eso implementa InputSplit y no una clase propia.
 *
 * InputSplit en Flink 1.20 pide un solo metodo: getSplitNumber().
 * Los datos del reparto los lleva getters propios, porque Flink solo usa
 * el numero para saber en que subtarea va el split.
 */
public class MongoSplit implements InputSplit {

    private static final long serialVersionUID = 1L;

    private final int    numeroSubtarea;   // 0, 1, 2, 3
    private final int    totalSubtareas;   // 4
    private final String letraInicio;      // "A"  primera letra que procesa
    private final String letraFin;         // "M"  primera letra que NO procesa
    private final long   limiteInferior;   // para mostrar el reparto

    public MongoSplit(int numeroSubtarea, int totalSubtareas,
                      String letraInicio, String letraFin,
                      long limiteInferior) {
        this.numeroSubtarea  = numeroSubtarea;
        this.totalSubtareas  = totalSubtareas;
        this.letraInicio     = letraInicio;
        this.letraFin        = letraFin;
        this.limiteInferior  = limiteInferior;
    }

    /** Metodo que Flink 1.20 exige a todo InputSplit. */
    @Override
    public int getSplitNumber() {
        return numeroSubtarea;
    }

    public int    getNumeroSubtarea() { return numeroSubtarea; }
    public int    getTotalSubtareas() { return totalSubtareas; }
    public String getLetraInicio()    { return letraInicio; }
    public String getLetraFin()       { return letraFin; }
    public long   getLimiteInferior() { return limiteInferior; }

    @Override
    public String toString() {
        return "subtarea " + numeroSubtarea + "/" + totalSubtareas
             + "  [" + letraInicio + " .. " + letraFin + ")";
    }
}
