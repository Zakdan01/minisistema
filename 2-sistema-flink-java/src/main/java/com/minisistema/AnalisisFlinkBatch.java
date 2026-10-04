package com.minisistema;

import com.minisistema.flink.MongoDensidadOutputFormat;
import com.minisistema.flink.MongoPaisesInputFormat;
import com.minisistema.flink.MongoResumenOutputFormat;
import com.minisistema.flink.MongoTopPaisesOutputFormat;
import com.minisistema.modelo.DensidadRegion;
import com.minisistema.modelo.IndicadoresRegion;
import com.minisistema.modelo.Pais;
import com.minisistema.modelo.PaisRanking;
import com.minisistema.modelo.TopPaisesRegion;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.bson.Document;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import java.util.List;

/**
 * JOB POR LOTES: agrega los 250 paises de MongoDB por region.
 *
 * El pipeline tiene TRES ramas que salen de la misma fuente. Las tres leen
 * los mismos 250 paises y las tres agrupan por region, pero cada una lleva
 * un modelo distinto, porque no todos los totales se calculan igual:
 *
 *   RAMA 1 - Los totales (la original)
 *     map   -> IndicadoresRegion con un solo pais dentro
 *     reduce -> fusiona hasta dejar 6 resultados
 *
*   RAMA 2 - El Top 5 de mas poblados por region
 *     map   -> TopPaisesRegion con un solo pais dentro
 *     reduce -> fusiona las listas y recorta a los 5 mejores
 *
 *   RAMA 3 - La densidad real frente a la del promedio
 *     map   -> DensidadRegion con un solo pais dentro
 *     reduce -> suma poblaciones y superficies; la division va al final
 *
 * QUE TIENE EN COMUN LAS TRES
 *
 *   1. createInputFormat   ->  Flink lee los paises de MongoDB, repartidos
 *                             en N subtareas, cada una con su conexion
 *
 *   2. map()               ->  cada pais se convierte en un acumulado con un
 *                             solo pais dentro (1 a 1)
 *
 *   3. groupBy(region)     ->  agrupa los 250 por region, sin barajar
 *
 *   4. reduce()            ->  fusiona los acumulados de cada region, hasta
 *                             dejar 6 resultados
 *
 *   5. output()            ->  Flink escribe los 6 en MongoDB
 *
 * El reduce() es la parte clave: es asociativo y conmutativo, asi que
 * Flink puede fusionar en cualquier orden y repartirse el trabajo entre
 * las subtareas sin que el resultado cambie. Por eso los totales salen
 * siempre igual aunque cambies el paralelismo.
 *
 * LAS TRES RAMAS LEEN LA FUENTE POR SEPARADO
 *
 * No es un defecto, es una decision: con 250 documentos, leer tres veces es
 * instantaneo, y a cambio cada rama queda completamente aislada de las otras
 * dos. Si se mezclaran en un unico map, los tres modelos quedarian acoplados
 * y cambiar uno obligaria a revisar los otros dos.
 *
 * Mundo.paises NO se escribe nunca: es solo la fuente de datos.
 */
public class AnalisisFlinkBatch {

    public static void main(String[] args) throws Exception {

        final String uri       = Config.texto("mongo.uri", "mongodb://localhost:27017");
        final String base      = Config.texto("mongo.base", "Mundo");
        final String colPaises = Config.texto("mongo.coleccionPaises", "paises");
        final String colResumen= Config.texto("mongo.coleccionResumen", "resumen_regiones");
        final String colTop    = Config.texto("mongo.coleccionTop", "top_paises_region");
        final String colDens   = Config.texto("mongo.coleccionDensidad", "densidad_regiones");
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
        System.out.println("  Escribe en tres colecciones:");
        System.out.println("    " + colResumen + "  (totales por region)");
        System.out.println("    " + colTop    + "  (top 5 de poblacion por region)");
        System.out.println("    " + colDens   + "  (densidad real frente a la del promedio)");
        System.out.println();

        // Se borran las TRES antes de empezar. Si solo se borrara la primera,
        // las otras dos guardarian datos de una corrida vieja y la pagina
        // mostraria rankings que ya no corresponden a los paises actuales.
        borrarResumenPrevio(uri, base, colResumen);
        borrarResumenPrevio(uri, base, colTop);
        borrarResumenPrevio(uri, base, colDens);

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
                .name("Map-Totales");

        System.out.println("  [2] map: cada pais genera su acumulado inicial.");
        System.out.println();

        // ---------------------------------------------------------------
        //  3. GROUP BY + 4. REDUCE
        // ---------------------------------------------------------------
        DataSet<IndicadoresRegion> porRegion = porPais
                .groupBy(IndicadoresRegion::getRegion)
                .reduce(IndicadoresRegion::sumar)
                .name("reduce-totales");

        System.out.println("  [3] groupBy: agrupado por region.");
        System.out.println("  [4] reduce: fusionando los acumulados.");
        System.out.println();

        // ---------------------------------------------------------------
        //  RAMA 2: el Top 5 de mas poblados por region
        // ---------------------------------------------------------------
        //  Misma estructura que la rama 1, pero el reduce fusiona listas de
        //  paises en vez de sumar cifras, y se queda con los 5 mayores.
        //
        //  El recorte a 5 se hace DENTRO de sumar(), nunca en el map(). Si se
        //  descartara en el map, un pais que no entraba en su tramo podria
        //  haber entrado en otro, y el resultado dependeria del reparto.
        DataSet<TopPaisesRegion> topPorPais = paises
                .map(TopPaisesRegion::desdePais)
                .name("Map-Top");

        DataSet<TopPaisesRegion> topPorRegion = topPorPais
                .groupBy(TopPaisesRegion::getRegion)
                .reduce(TopPaisesRegion::sumar)
                .name("reduce-top");

        System.out.println("  [3b] groupBy: agrupado por region (top 5).");
        System.out.println("  [4b] reduce: fusionando los rankings.");
        System.out.println();

        // ---------------------------------------------------------------
        //  RAMA 3: la densidad real frente a la del promedio
        // ---------------------------------------------------------------
        //  Aqui el reduce SOLO suma. La densidad es una division, y dividir
        //  no es conmutativo: por eso se calcula despues, en los getters.
        DataSet<DensidadRegion> densPorPais = paises
                .map(DensidadRegion::desdePais)
                .name("Map-Densidad");

        DataSet<DensidadRegion> densPorRegion = densPorPais
                .groupBy(DensidadRegion::getRegion)
                .reduce(DensidadRegion::sumar)
                .name("reduce-densidad");

        System.out.println("  [3c] groupBy: agrupado por region (densidad).");
        System.out.println("  [4c] reduce: sumando poblaciones y superficies.");
        System.out.println();

        // ---------------------------------------------------------------
        //  5. SUMIDEROS
        // ---------------------------------------------------------------
        //  Los tres de golpe: un solo execute() los dispara a todos, y cada
        //  uno abre su conexion a MongoDB por su cuenta.
        porRegion.output(new MongoResumenOutputFormat(uri, base, colResumen))
                  .name("Sumidero-Totales");

        topPorRegion.output(new MongoTopPaisesOutputFormat(uri, base, colTop))
                    .name("Sumidero-Top");

        densPorRegion.output(new MongoDensidadOutputFormat(uri, base, colDens))
                     .name("Sumidero-Densidad");

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
        System.out.println("      escrito en " + base + "." + colTop);
        System.out.println("      escrito en " + base + "." + colDens);
        System.out.println();

        if (mostrar) {
            mostrarResultado(uri, base, colResumen);
            mostrarTop(uri, base, colTop);
            mostrarDensidad(uri, base, colDens);
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

    /**
     * Muestra el Top 5 por region que escribio el job.
     *
     * Se lee el arreglo embebido del documento, que Flink ya dejo ordenado de
     * mas a menos poblacion, asi que aqui no hay que reordenar nada.
     */
    private static void mostrarTop(String uri, String base, String coleccion) {
        try (MongoClient cliente = MongoClients.create(uri)) {
            MongoCollection<Document> c = cliente.getDatabase(base)
                                                  .getCollection(coleccion);

            System.out.println("--------------------------------------------------------");
            System.out.println("  TOP 5 MAS POBLADOS POR REGION");
            System.out.println("--------------------------------------------------------");

            for (Document d : c.find().sort(new Document("region", 1))) {
                System.out.println("  " + d.getString("region"));

                List<?> items = (List<?>) d.get("top");
                if (items == null) {
                    continue;
                }
                for (Object o : items) {
                    Document p = (Document) o;
                    System.out.printf("    %d. %-30s %12d%n",
                            ((Number) p.get("puesto")).intValue(),
                            p.getString("nombre"),
                            ((Number) p.get("poblacion")).longValue());
                }
            }
            System.out.println("--------------------------------------------------------");
        }
    }

    /**
     * Muestra las dos densidades de cada region.
     *
     * La ultima columna es el punto de todo esto: cuanto se equivoca el
     * promedio de densidades respecto de la densidad real. Sale enorme en
     * Antartica y en Oceania, y se ve a simple vista que promediar no vale.
     */
    private static void mostrarDensidad(String uri, String base, String coleccion) {
        try (MongoClient cliente = MongoClients.create(uri)) {
            MongoCollection<Document> c = cliente.getDatabase(base)
                                                  .getCollection(coleccion);

            System.out.println("--------------------------------------------------------");
            System.out.printf("  %-10s %14s %16s %12s%n",
                    "REGION", "REAL hab/km2", "PROMEDIO (mal)", "ERROR");
            System.out.println("--------------------------------------------------------");

            for (Document d : c.find().sort(new Document("region", 1))) {
                System.out.printf("  %-10s %14.2f %16.2f %11.1f%%%n",
                        d.getString("region"),
                        ((Number) d.get("densidad_real")).doubleValue(),
                        ((Number) d.get("densidad_promedio")).doubleValue(),
                        ((Number) d.get("error_porcentaje")).doubleValue());
            }

            System.out.println("--------------------------------------------------------");
            System.out.println("  REAL     = poblacion total / superficie total");
            System.out.println("  PROMEDIO = media de las densidades de cada pais");
            System.out.println("--------------------------------------------------------");
        }
    }
}
