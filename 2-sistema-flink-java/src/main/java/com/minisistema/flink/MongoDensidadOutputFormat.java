package com.minisistema.flink;

import com.minisistema.modelo.DensidadRegion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.bson.Document;

/**
 * EL SUMIDERO DE LA DENSIDAD: escribe las dos densidades por region.
 *
 * Es el tercer sumidero del job. Recibe los 6 objetos de DensidadRegion y
 * deja UN documento por region en la coleccion densidad_regiones.
 *
 * CADA DOCUMENTO GUARDA TRES CIFRAS
 *
 *   densidad_real          = poblacion total / superficie total
 *   densidad_promedio      = la media de las densidades sueltas
 *   error_porcentaje       = cuanto se equivoca el promedio
 *
 * Los documentos se guardan con un documento por region (6 en total), igual
 * que en los otros dos sumideros, para que la interfaz los liste con un
 * simple find().sort() y no se acumulen filas viejas de corridas previas.
 */
public class MongoDensidadOutputFormat extends RichOutputFormat<DensidadRegion> {

    private static final long serialVersionUID = 1L;

    private final String uri;
    private final String base;
    private final String coleccion;

    private transient MongoClient cliente;
    private transient MongoCollection<Document> coleccionDensidad;

    private int registrosEscritos = 0;

    public MongoDensidadOutputFormat(String uri, String base, String coleccion) {
        this.uri       = uri;
        this.base      = base;
        this.coleccion = coleccion;
    }

    @Override
    public void configure(Configuration parameters) {
        // La configuracion ya viaja en los tres campos finales de arriba.
    }

    @Override
    public void open(int numTasks, int numSplitsTotal) {
        this.cliente = MongoClients.create(uri);
        this.coleccionDensidad = cliente.getDatabase(base).getCollection(coleccion);
        this.registrosEscritos = 0;
    }

    @Override
    public void writeRecord(DensidadRegion densidad) {

        // OJO con los tipos: el driver devuelve los enteros de 32 bits de
        // MongoDB como Integer, asi que las cifras grandes se leen como
        // Number y se pasan con longValue(), no con getLong().
        double real     = densidad.getDensidadReal();
        double promedio = densidad.getDensidadPromedio();

        Document documento = new Document("region",             densidad.getRegion())
                .append("cantidad_paises",      densidad.getCantidadPaises())
                .append("poblacion_total",      densidad.getPoblacionTotal())
                .append("superficie_total",     Math.round(densidad.getSuperficieTotal()))
                .append("paises_con_superficie", densidad.getPaisesConSuperficie())
                .append("densidad_real",        Math.round(real * 100.0) / 100.0)
                .append("densidad_promedio",    Math.round(promedio * 100.0) / 100.0)
                .append("error_porcentaje",     Math.round(densidad.getErrorPorcentaje() * 10.0) / 10.0);

        // replaceOne con upsert: re-ejecutar el job no duplica nada.
        Document filtro = new Document("region", densidad.getRegion());
        coleccionDensidad.replaceOne(filtro, documento, new ReplaceOptions().upsert(true));

        registrosEscritos++;
    }

    @Override
    public void close() {
        if (cliente != null) {
            cliente.close();
            cliente = null;
            coleccionDensidad = null;
        }
    }

    public int getRegistrosEscritos() {
        return registrosEscritos;
    }
}