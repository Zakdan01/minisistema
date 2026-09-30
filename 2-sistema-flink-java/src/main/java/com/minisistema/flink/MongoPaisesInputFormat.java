package com.minisistema.flink;

import com.minisistema.modelo.Pais;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplitAssigner;
import org.bson.Document;

/**
 * LA FUENTE: lee los paises de MongoDB.
 *
 * Aqui es donde el trabajo se reparte de verdad. Flink llama a
 *
 *   createInputSplits()   una vez, en el cliente, y de aqui sale el reparto
 *   openInputFormat()     en cada subtarea, y aqui cada una abre SU conexion
 *   open(split)           en cada subtarea, una vez por split
 *
 * El reparto se calcula contando primero cuantos paises hay por cada letra
 * inicial del codigo. Con los 250 codigos de tres letras (ABW, AFG, ... ZWE)
 * los cortes caen en 62-63 paises por subtarea, y MongoDB resuelve cada
 * consulta con su indice de _id sin recorrer la coleccion entera.
 *
 * Flink 1.20 usa estas firmas exactas:
 *
 *   void open(T split)                en vez de devolver el split
 *   OT  nextRecord(OT reuse)         en vez de nextRecord() a secas
 *   createInputSplits(int numSplits)  un solo parametro
 *
 */
public class MongoPaisesInputFormat extends RichInputFormat<Pais, MongoSplit> {

    private static final long serialVersionUID = 1L;

    private final String uri;
    private final String base;
    private final String coleccion;

    /** Solo existe dentro de la subtarea, y se cierra al terminar. */
    private transient MongoClient cliente;
    private transient MongoCollection<Document> coleccionPaises;
    private transient MongoCursor<Document> cursor;

    /** Se recicla en nextRecord, para no crear un Pais por documento. */
    private transient Pais reutilizable;

    public MongoPaisesInputFormat(String uri, String base, String coleccion) {
        this.uri       = uri;
        this.base      = base;
        this.coleccion = coleccion;
    }

    // -----------------------------------------------------------------------
    //  Metodos que Flink 1.20 exige implementar
    // -----------------------------------------------------------------------

    @Override
    public void configure(Configuration parameters) {
        // No hace falta nada: la configuracion ya viaja en los
        // tres campos finales de arriba.
    }

    @Override
    public org.apache.flink.api.common.io.statistics.BaseStatistics
            getStatistics(org.apache.flink.api.common.io.statistics.BaseStatistics
                          reutilizarEstadisticas) {
        // Aqui se podria poner un countDocuments, pero el planificador de
        // Flink no lo necesita para este job: los splits se crean siempre.
        return reutilizarEstadisticas;
    }

    @Override
    public InputSplitAssigner getInputSplitAssigner(MongoSplit[] splits) {
        // LocalInputSplitAssigner reparte un split por subtarea, que es
        // justo lo que queremos: cada subtarea lee su tramo.
        return new org.apache.flink.api.common.io.DefaultInputSplitAssigner(splits);
    }

    // -----------------------------------------------------------------------
    //  1. REPARTO DEL TRABAJO - se calcula una vez, en el cliente
    // -----------------------------------------------------------------------

    @Override
    public MongoSplit[] createInputSplits(int numeroSubtareas) throws java.io.IOException {

        final int subtareas = Math.min(numeroSubtareas, 26);
        final long[] acumulado;
        final long total;

        MongoClient temporal = MongoClients.create(uri);
        try {
            MongoCollection<Document> c = temporal.getDatabase(base)
                                                    .getCollection(coleccion);

            long[] porLetra = new long[26];
            for (String codigo : c.distinct("_id", String.class)) {
                int letra = indiceDe(codigo);
                if (letra >= 0) {
                    porLetra[letra]++;
                }
            }

            acumulado = new long[26];
            long corriendo = 0;
            for (int i = 0; i < 26; i++) {
                corriendo += porLetra[i];
                acumulado[i] = corriendo;
            }
            total = acumulado[25];

        } finally {
            temporal.close();
        }

        MongoSplit[] splits = new MongoSplit[subtareas];
        int corte = 0;

        for (int i = 0; i < subtareas; i++) {

            // Division en decimal, no entera, para que no se pierdan los ultimos.
            long objetivo = (long) Math.floor((double) total * (i + 1) / subtareas);

            // La letra donde cae el corte. Si el objetivo cae en una letra
            // ya consumida, se queda en la letra actual: asi los splits
            // quedan pegados, sin huecos ni solapamientos.
            int letra = corte;
            while (letra < 26 && acumulado[letra] <= objetivo) {
                letra++;
            }

            splits[i] = new MongoSplit(
                    i,
                    subtareas,
                    letraDe(corte),
                    letraDe(letra),
                    (long) Math.floor((double) total * i / subtareas)
            );

            corte = letra;
        }

        return splits;
    }

    // -----------------------------------------------------------------------
    //  2. Cada subtarea abre SU PROPIA conexion
    // -----------------------------------------------------------------------

    @Override
    public void openInputFormat() {
        this.cliente = MongoClients.create(uri);
        this.coleccionPaises = cliente.getDatabase(base).getCollection(coleccion);
        this.reutilizable = new Pais("", "", "", 0L, 0.0);
    }

    // -----------------------------------------------------------------------
    //  3. La subtarea abre SU cursor sobre SU intervalo
    // -----------------------------------------------------------------------

    @Override
    public void open(MongoSplit split) {
        this.cursor = documentosDelSplit(split).iterator();
    }

    // -----------------------------------------------------------------------
    //  4. Leer hasta que se acaba
    // -----------------------------------------------------------------------

    @Override
    public boolean reachedEnd() {
        return cursor == null || !cursor.hasNext();
    }

    @Override
    public Pais nextRecord(Pais reutilizar) {
        if (cursor == null || !cursor.hasNext()) {
            return null;
        }
        return Pais.copiarEn(reutilizar, cursor.next());
    }

    // -----------------------------------------------------------------------
    //  5. Cierre
    // -----------------------------------------------------------------------

    @Override
    public void close() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }

    @Override
    public void closeInputFormat() {
        close();
        if (cliente != null) {
            cliente.close();
            cliente = null;
            coleccionPaises = null;
        }
    }

    // -----------------------------------------------------------------------
    //  El filtro de cada split
    // -----------------------------------------------------------------------

    /**
     * Filtra por el intervalo de letras de este split.
     *
     * Se usa $gte y $lt sobre _id. Como los codigos van de A a Z, un
     * intervalo de letras es un intervalo de codigos, y MongoDB lo resuelve
     * con el indice de _id sin recorrer toda la coleccion.
     */
    private FindIterable<Document> documentosDelSplit(MongoSplit split) {
        Document filtro = new Document("_id",
                new Document("$gte", split.getLetraInicio())
                        .append("$lt", split.getLetraFin()));
        return coleccionPaises.find(filtro).sort(new Document("_id", 1));
    }

    // -----------------------------------------------------------------------
    //  Utilidades de letras
    // -----------------------------------------------------------------------

    private static int indiceDe(String codigo) {
        if (codigo == null || codigo.isEmpty()) {
            return -1;
        }
        char c = Character.toUpperCase(codigo.charAt(0));
        if (c < 'A' || c > 'Z') {
            return -1;
        }
        return c - 'A';
    }

    private static String letraDe(int indice) {
        if (indice < 0) {
            return "A";
        }
        if (indice >= 26) {
            return "ZZ";
        }
        return String.valueOf((char) ('A' + indice));
    }
}
