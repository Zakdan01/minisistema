package com.minisistema;

import com.minisistema.flink.MongoPaisesInputFormat;
import com.minisistema.flink.MongoResumenOutputFormat;
import com.minisistema.modelo.IndicadoresRegion;
import com.minisistema.modelo.Pais;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.bson.Document;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * JOB POR LOTES: agrega los 250 paises de MongoDB por region.
 *
 * El pipeline completo son cuatro pasos:
 *
 *   1. createInputFormat   ->  Flink lee los paises de MongoDB, repartidos
 *                             en N subtareas, cada una con su conexion
 *
 *   2. map()               ->  cada pais se convierte en un IndicadoresRegion
 *                             con un solo pais dentro (1 a 1)
 *
 *   3. groupBy(region)     ->  agrupa los 250 por region, sin barajar
 *
 *   4. reduce()            ->  fusiona los acumulados de cada region,
 *                             sumando hasta dejar 6 resultados
 *
 *   5. output()            ->  Flink escribe los 6 en MongoDB
 *
 * El reduce() es la parte clave: es asociativo y conmutativo, asi que
 * Flink puede fusionar en cualquier orden y repartirse el trabajo entre
 * las subtareas sin que el resultado cambie. Por eso el total sale
 * siempre igual aunque cambies el paralelismo.
 */
public class AnalisisFlinkBatch {

    public static void main(String[] args) throws Exception {

        final String uri       = Config.texto("mongo.uri", "mongodb://localhost:27017");
        final String base      = Config.texto("mongo.base", "Mundo");
        final String colPaises = Config.texto("mongo.coleccionPaises", "paises");
        final String colResumen= Config.texto("mongo.coleccionResumen", "resumen_regiones");
        final int    paralelismo = Config.entero("flink.paralelismo", 4);
        final long   umbral      = Config.largo("umbralPoblacionGrande", 50_000_000L);
        final boolean mostrar    = Config.booleano("mostrarResultadoConsola", true);

        System.out.println("========================================================");
        System.out.println("  JOB POR LOTES CON APACHE FLINK");
        System.out.println("========================================================");
        System.out.println("  MongoDB      : " + uri);
        System.out.println("  Base         : " + base);
        System.out.println("  Coleccion    : " + colPaises);
        System.out.println("  Paralelismo  : " + paralelismo + " subtareas");
        System.out.println("  Umbral grande: " + umbral + " habitantes");
        System.out.println();

        borrarResumenPrevio(uri, base, colResumen);

        // ---------------------------------------------------------------
        //  El entorno de Flink. Con paralelismo mayor que 1 levanta un
        //  mini-cluster dentro de la JVM y reparte las subtareas.
        // ---------------------------------------------------------------
        ExecutionEnvironment entorno =
                ExecutionEnvironment.createLocalEnvironment(paralelismo);

        // ---------------------------------------------------------------
        //  1. FUENTE
        // ---------------------------------------------------------------
        //  1. FUENTE
        //
        //  createInput devuelve un DataSource, que tiene los mismos
        //  metodos que un DataSet mas los de codigo: split, name, output.
        //  Flink exige un TypeInformation explicito para saber como
        //  serializar los objetos Pais.
        // ---------------------------------------------------------------
        DataSource<Pais> paises = entorno.createInput(
                new MongoPaisesInputFormat(uri, base, colPaises),
                TypeInformation.of(Pais.class));

        // Flink recorta los nombres de mas de 80 caracteres y avisa por
        // consola. Se le pone uno corto a cada operador.
        paises.name("Fuente-MongoDB");

        System.out.println("  [1] Fuente lista. Reparto en " + paralelismo + " subtareas.");
        System.out.println();

        // ---------------------------------------------------------------
        //  2. MAP: pais -> acumulado de un solo pais
        // ---------------------------------------------------------------
        DataSet<IndicadoresRegion> porPais = paises
                .map(p -> IndicadoresRegion.desdePais(p, umbral))
                .name("Map");

        System.out.println("  [2] map: cada pais genera su acumulado inicial.");
        System.out.println();

        // ---------------------------------------------------------------
        //  3. GROUP BY + 4. REDUCE
        // ---------------------------------------------------------------
        DataSet<IndicadoresRegion> porRegion = porPais
                .groupBy(IndicadoresRegion::getRegion)
                .reduce(IndicadoresRegion::sumar)
                .name("reduce-por-region");

        System.out.println("  [3] groupBy: agrupado por region.");
        System.out.println("  [4] reduce: fusionando los acumulados.");
        System.out.println();

        // ---------------------------------------------------------------
        //  5. SUMIDERO
        // ---------------------------------------------------------------
        porRegion.output(new MongoResumenOutputFormat(uri, base, colResumen))
                  .name("Sumidero");

        // ---------------------------------------------------------------
        //  El execute() es el que dispara todo. Hasta aqui no se ha
        //  leido ni un solo documento de la base de datos.
        // ---------------------------------------------------------------
        long inicio = System.currentTimeMillis();
        entorno.execute("agregar-paises-por-region");
        long duracion = System.currentTimeMillis() - inicio;

        System.out.println();
        System.out.println("  [5] Job terminado en " + duracion + " ms.");
        System.out.println("      escrito en " + base + "." + colResumen);
        System.out.println();

        if (mostrar) {
            mostrarResultado(uri, base, colResumen);
        }
    }

    /**
     * Borra los resultados del run anterior para que el job de hoy
     * sea el unico contenido de la coleccion.
     *
     * Esto NO toca Mundo.paises, que es la fuente de datos.
     */
    private static void borrarResumenPrevio(String uri, String base, String coleccion) {
        try (MongoClient cliente = MongoClients.create(uri)) {
            long borrados = cliente.getDatabase(base)
                                   .getCollection(coleccion)
                                   .deleteMany(new Document())
                                   .getDeletedCount();
            if (borrados > 0) {
                System.out.println("  Se borraron " + borrados
                                 + " resultados del run anterior de " + coleccion + ".");
                System.out.println();
            }
        }
    }

    /** Muestra lo que Flink escribio, leyendolo de MongoDB. */
    private static void mostrarResultado(String uri, String base, String coleccion) {
        try (MongoClient cliente = MongoClients.create(uri)) {
            MongoCollection<Document> c = cliente.getDatabase(base)
                                                  .getCollection(coleccion);

            System.out.println("--------------------------------------------------------");
            System.out.printf("  %-10s %7s %17s %14s %8s  %s%n",
                    "REGION", "PAISES", "POBLACION", "SUPERFICIE", "GRANDES", "PAIS MAS POBLADO");
            System.out.println("--------------------------------------------------------");

            long totalPaises = 0;
            long totalPoblacion = 0;
            long totalSuperficie = 0;
            long totalGrandes = 0;

            for (Document d : c.find().sort(new Document("region", 1))) {

                // OJO: se lee como Number y se convierte con longValue().
                // No sirve d.getLong(...) aqui, porque el driver devuelve
                // los enteros de 32 bits de MongoDB como Integer, y al
                // pedir un Long lanza ClassCastException.
                long paises      = ((Number) d.get("cantidad_paises")).longValue();
                long pobla       = ((Number) d.get("poblacion_total")).longValue();
                long superficie  = ((Number) d.get("superficie_total")).longValue();
                long grandes     = ((Number) d.get("cantidad_grandes")).longValue();

                totalPaises     += paises;
                totalPoblacion  += pobla;
                totalSuperficie += superficie;
                totalGrandes    += grandes;

                System.out.printf("  %-10s %7d %17d %14d %8d  %s%n",
                        d.getString("region"), paises, pobla, superficie,
                        grandes, d.getString("pais_mas_poblado"));
            }

            System.out.println("--------------------------------------------------------");
            System.out.printf("  %-10s %7d %17d %14d %8d%n",
                    "TOTAL", totalPaises, totalPoblacion, totalSuperficie, totalGrandes);
            System.out.println("--------------------------------------------------------");
        }
    }
}
