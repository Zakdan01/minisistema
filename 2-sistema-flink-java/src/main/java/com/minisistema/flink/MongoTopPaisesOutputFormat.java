package com.minisistema.flink;

import com.minisistema.modelo.PaisRanking;
import com.minisistema.modelo.TopPaisesRegion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * EL SUMIDERO DEL TOP 5: escribe los rankings por region en MongoDB.
 *
 * Es el segundo sumidero del job. Recibe los 6 objetos de TopPaisesRegion
 * que produjo el reduce y deja UN documento por region en la coleccion
 * top_paises_region, con los 5 paises guardados como un arreglo.
 *
 * POR QUE UN DOCUMENTO POR REGION Y NO UNO POR PAIS
 *
 * Un documento por region (6 en total) hace que el listado de la interfaz
 * sea un simple find().sort(). Si en cambio se guardara un documento por
 * puesto, al re-ejecutar el job un pais que ayer era el numero 5 y hoy es el
 * numero 9 dejaria su fila vieja en la base, y el ranking tendria huecos.
 * Con un solo documento por region, el upsert lo reemplaza entero.
 *
 * Flink llama open() una vez, writeRecord() una vez por region, y close()
 * al acabar. La conexion se abre en open() y se cierra en close(): si se
 * abriera por cada documento se perderian 5 de 6 conexiones.
 */
public class MongoTopPaisesOutputFormat extends RichOutputFormat<TopPaisesRegion> {

    private static final long serialVersionUID = 1L;

    private final String uri;
    private final String base;
    private final String coleccion;

    private transient MongoClient cliente;
    private transient MongoCollection<Document> coleccionTop;

    private int registrosEscritos = 0;

    public MongoTopPaisesOutputFormat(String uri, String base, String coleccion) {
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
        this.coleccionTop = cliente.getDatabase(base).getCollection(coleccion);
        this.registrosEscritos = 0;
    }

    @Override
    public void writeRecord(TopPaisesRegion ranking) {

        // El top 5 se guarda como un arreglo de documentos embebidos, en el
        // orden en que lo dejo el reduce (de mas a menos poblacion).
        ArrayList<Document> items = new ArrayList<>();
        for (PaisRanking p : ranking.getTop()) {
            items.add(new Document("puesto",    items.size() + 1)
                                .append("codigo",   p.getCodigo())
                                .append("nombre",   p.getNombre())
                                .append("poblacion", p.getPoblacion())
                                .append("superficie_km2", Math.round(p.getSuperficieKm2())));
        }

        Document documento = new Document("region",       ranking.getRegion())
                .append("cantidad_paises", ranking.getTop().size())
                .append("top",            items);

        // replaceOne con upsert en vez de insertOne: si el job se ejecuta dos
        // veces no queda duplicado, cada region queda actualizada en su sitio.
        Document filtro = new Document("region", ranking.getRegion());
        coleccionTop.replaceOne(filtro, documento, new ReplaceOptions().upsert(true));

        registrosEscritos++;
    }

    @Override
    public void close() {
        if (cliente != null) {
            cliente.close();
            cliente = null;
            coleccionTop = null;
        }
    }

    public int getRegistrosEscritos() {
        return registrosEscritos;
    }
}