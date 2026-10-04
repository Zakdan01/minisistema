package com.minisistema.modelo;

import java.util.ArrayList;
import java.util.List;

/**
 * El Top 5 de paises mas poblados de UNA region.
 *
 * Es el segundo valor que viaja por un reduce de Flink. Cada vez que dos
 * subtareas intercambian acumulados, sus listas se fusionan aqui dentro de
 * sumar() y el resultado vuelve al circulo. Al final hay un solo objeto por
 * region con los 5 paises mas poblados de esa region.
 *
 * POR QUE ESTO SIGUE SIENDO UN REDUCE VALIDO
 *
 * Un reduce de Flink solo sirve si la fusion es asociativa y conmutativa,
 * porque el resultado no puede depender del orden en que la red entregue
 * las subtareas. Y este caso lo cumple:
 *
 *     fusion(A, B) = los 5 mayores de (A UNION B)
 *
 * Da igual si se fusiona primero el 1 con el 2, o el 3 con el 4. El conjunto
 * de candidatos es el mismo y el recorte se hace al final. Por eso los
 * numeros del ranking salen siempre iguales, aunque cambies el paralelismo
 * o el orden de lectura de MongoDB.
 *
 * OJO con el recorte: si se guardaran solo 5, se descartarian los demas
 * durante el map(), y eso SI dependeria del reparto. Por eso la lista solo
 * se recorta dentro de sumar(), cuando los candidatos ya estan completos.
 */
public class TopPaisesRegion {

    /** Cuantos paises se quedan por region. */
    public static final int LIMITE = 5;

    private final String region;
    private final List<PaisRanking> top;

    public TopPaisesRegion(String region) {
        this.region = region;
        this.top    = new ArrayList<>(LIMITE);
    }

    public String getRegion() { return region; }
    public List<PaisRanking> getTop() { return top; }

    /**
     * Crea el ranking inicial a partir de un pais.
     * Lo invoca el map() de Flink, una vez por cada uno de los 250.
     */
    public static TopPaisesRegion desdePais(Pais pais) {
        TopPaisesRegion t = new TopPaisesRegion(pais.getRegion());
        t.agregar(PaisRanking.desdePais(pais));
        return t;
    }

    /**
     * Fusiona este ranking con el de otra region identica.
     * Lo invoca el reduce() de Flink.
     *
     * La lista nueva se crea siempre: si se reutilizara la de uno de los dos
     * lados, Flink podria estar fusionando un objeto consigo mismo en una
     * red de pruebas, y la lista se corromperia.
     */
    public TopPaisesRegion sumar(TopPaisesRegion otro) {
        List<PaisRanking> mezclada = new ArrayList<>(this.top.size() + otro.top.size());
        mezclada.addAll(this.top);
        mezclada.addAll(otro.top);

        // Collections.sort usa el compareTo de PaisRanking: mas poblacion
        // primero, y a igualdad el codigo mas pequeno.
        mezclada.sort(null);

        // Recorte a los 5 mejores.
        while (mezclada.size() > LIMITE) {
            mezclada.remove(mezclada.size() - 1);
        }

        this.top.clear();
        this.top.addAll(mezclada);
        return this;
    }

    /** Inserta un pais en su sitio y descarta el que quede fuera del Top 5. */
    private void agregar(PaisRanking pais) {
        this.top.add(pais);
        this.top.sort(null);
        while (this.top.size() > LIMITE) {
            this.top.remove(this.top.size() - 1);
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(region).append(":");
        for (PaisRanking p : top) {
            s.append(' ').append(p.getCodigo());
        }
        return s.toString();
    }
}