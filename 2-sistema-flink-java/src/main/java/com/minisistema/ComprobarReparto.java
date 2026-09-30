package com.minisistema;

import com.minisistema.flink.MongoPaisesInputFormat;
import com.minisistema.flink.MongoSplit;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import java.util.ArrayList;
import java.util.List;

/**
 * COMPROBACION DEL REPARTO.
 *
 * Esta clase NO es el job. Sirve para responder a la pregunta de como se
 * reparten los 250 paises entre las subtareas, y para verificar que el
 * reparto cubre los 250 sin huecos ni repetidos.
 *
 * Sirve para la defensa: muestra que createInputSplits() genera splits
 * reales, y que el filtro de letras de cada split agarra su trozo.
 */
public class ComprobarReparto {

    public static void main(String[] args) throws Exception {

        String uri  = Config.texto("mongo.uri", "mongodb://localhost:27017");
        String base = Config.texto("mongo.base", "Mundo");
        String col  = Config.texto("mongo.coleccionPaises", "paises");
        int para    = Config.entero("flink.paralelismo", 4);

        MongoPaisesInputFormat fuente = new MongoPaisesInputFormat(uri, base, col);

        System.out.println("========================================================");
        System.out.println("  REPARTO DE LAS 250 EN " + para + " SUBTAREAS");
        System.out.println("========================================================");

        MongoSplit[] splits = fuente.createInputSplits(para);
        fuente.closeInputFormat();

        int totalAcumulado = 0;
        List<String> codigosVistos = new ArrayList<>();

        try (MongoClient cliente = MongoClients.create(uri)) {
            MongoCollection<Document> c = cliente.getDatabase(base).getCollection(col);

            for (MongoSplit s : splits) {
                Document filtro = new Document("_id",
                        new Document("$gte", s.getLetraInicio())
                                .append("$lt",  s.getLetraFin()));

                List<String> codigos = new ArrayList<>();
                for (Document d : c.find(filtro).sort(new Document("_id", 1))) {
                    codigos.add(d.getString("_id"));
                }

                codigosVistos.addAll(codigos);
                totalAcumulado += codigos.size();

                String primero = codigos.isEmpty() ? "-" : codigos.get(0);
                String ultimo  = codigos.isEmpty() ? "-" : codigos.get(codigos.size() - 1);

                System.out.printf("  subtarea %d  letras [%s..%s)  %3d paises   %s .. %s%n",
                        s.getNumeroSubtarea(), s.getLetraInicio(), s.getLetraFin(),
                        codigos.size(), primero, ultimo);
            }

            System.out.println("--------------------------------------------------------");
            System.out.println("  Total leido por las splits : " + totalAcumulado);
            System.out.println("  Documentos en la coleccion: " + c.countDocuments());
            System.out.println("  Codigos unicos             : "
                    + codigosVistos.stream().distinct().count());

            boolean ok = totalAcumulado == c.countDocuments()
                      && codigosVistos.stream().distinct().count() == totalAcumulado;
            System.out.println("  Reparto correcto           : " + (ok ? "SI" : "NO"));
            System.out.println("--------------------------------------------------------");
        }
    }
}
