package com.minisistema;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Lee el archivo config.properties.
 *
 * Se usa un solo objeto estatico porque los valores son los mismos para
 * todo el job y no cambian mientras corre. La clase es inmutable, se
 * construye una vez y se lee desde cualquier parte.
 */
public final class Config {

    private static final Properties PROPIEDADES = new Properties();

    static {
        try (InputStream entrada = Config.class
                .getResourceAsStream("/config.properties")) {

            if (entrada == null) {
                throw new IllegalStateException(
                    "No se encontro config.properties dentro de target/classes. "
                  + "Compila el proyecto con: mvnw.cmd compile");
            }

            PROPIEDADES.load(entrada);

        } catch (IOException e) {
            throw new IllegalStateException(
                "No se pudo leer config.properties: " + e.getMessage(), e);
        }
    }

    private Config() {
    }

    /** Texto de una propiedad, o valorPorDefecto si no existe. */
    public static String texto(String clave, String valorPorDefecto) {
        return PROPIEDADES.getProperty(clave, valorPorDefecto).trim();
    }

    /** Entero de una propiedad. */
    public static int entero(String clave, int valorPorDefecto) {
        String valor = PROPIEDADES.getProperty(clave);
        if (valor == null || valor.isBlank()) {
            return valorPorDefecto;
        }
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                "La propiedad " + clave + " deberia ser un numero entero, "
              + "pero vale: " + valor, e);
        }
    }

    /** Numero largo de una propiedad. */
    public static long largo(String clave, long valorPorDefecto) {
        String valor = PROPIEDADES.getProperty(clave);
        if (valor == null || valor.isBlank()) {
            return valorPorDefecto;
        }
        try {
            return Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                "La propiedad " + clave + " deberia ser un numero entero, "
              + "pero vale: " + valor, e);
        }
    }

    /** Booleano de una propiedad. */
    public static boolean booleano(String clave, boolean valorPorDefecto) {
        String valor = PROPIEDADES.getProperty(clave);
        if (valor == null || valor.isBlank()) {
            return valorPorDefecto;
        }
        return Boolean.parseBoolean(valor.trim());
    }
}
