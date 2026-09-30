package com.minisistema.flink;

import com.minisistema.modelo.IndicadoresRegion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.bson.Document;

/**
 * EL SUMIDERO: escribe los resultados en MongoDB.
 *
 * Recibe los seis objetos de IndicadoresRegion que produjo el reduce y
 * los inserta en la coleccion resumen_regiones.
 *
 * Flink llama a open() y writeRecord() una vez por cada registro, y a
 * close() cuando acaba el job. La conexion se abre en open() y se cierra
 * en close(): si se abriera por cada documento se perderian 5 de 6
 * conexiones, y quedaria un aviso de conexion abierta por cada una.
 *
 * Para la defensa: esta clase es la prueba de que la escritura la hace
 * Flink. El job no devuelve los datos a Java para que los grabe a mano,
 * Flink los entrega aqui y aqui se guardan.
 */
public class MongoResumenOutputFormat extends RichOutputFormat<IndicadoresRegion> {

    private static final long serialVersionUID = 1L;

    private final String uri;
    private final String base;
    private final String coleccion;

    /** Se abre en open() y se cierra en close(). */
    private transient MongoClient cliente;
    private transient MongoCollection<Document> coleccionResumen;

    private int registrosEscritos = 0;

    public MongoResumenOutputFormat(String uri, String base, String coleccion) {
        this.uri       = uri;
        this.base      = base;
        this.coleccion = coleccion;
    }

    @Override
    public void configure(Configuration parameters) {
        // La configuracion ya viaja en los tres campos finales de arriba.
    }

    /**
     * Flink 1.20 llama a open(int numTasks, int numSplitsTotal).
     * Los dos parametros no hacen falta aqui: el sumidero escribe una
     * sola vez por region, y sin importar cuantas subtareas haya.
     */
    @Override
    public void open(int numTasks, int numSplitsTotal) {
        this.cliente = MongoClients.create(uri);
        this.coleccionResumen = cliente.getDatabase(base).getCollection(coleccion);
        this.registrosEscritos = 0;
    }

    @Override
    public void writeRecord(IndicadoresRegion indicadores) {

        Document documento = new Document("region",         indicadores.getRegion())
                .append("cantidad_paises",  indicadores.getCantidadPaises())
                .append("poblacion_total",  indicadores.getPoblacionTotal())
                .append("superficie_total", Math.round(indicadores.getSuperficieTotal()))
                .append("poblacion_maxima", indicadores.getPoblacionMaxima())
                .append("pais_mas_poblado", indicadores.getPaisMasPoblado())
                .append("cantidad_grandes", indicadores.getCantidadGrandes());

        // replaceOne con upsert en vez de insertOne:
        // si el job se ejecuta dos veces no queda duplicado, cada region
        // queda actualizada en su sitio.
        Document filtro = new Document("region", indicadores.getRegion());
        coleccionResumen.replaceOne(filtro, documento, new ReplaceOptions().upsert(true));

        registrosEscritos++;
    }

    @Override
    public void close() {
        if (cliente != null) {
            cliente.close();
            cliente = null;
            coleccionResumen = null;
        }
    }

    public int getRegistrosEscritos() {
        return registrosEscritos;
    }
}
