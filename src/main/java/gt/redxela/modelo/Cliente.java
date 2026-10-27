package gt.redxela.modelo;

import java.util.Objects;
import java.util.UUID;

/** Datos iniciales de una cuenta; las operaciones monetarias se definirán en otra etapa. */
public record Cliente(UUID id, String nombre, String cuenta) {
    public Cliente {
        Objects.requireNonNull(id, "id es obligatorio");
        nombre = requerido(nombre, "nombre");
        cuenta = requerido(cuenta, "cuenta");
    }

    public Cliente(String nombre, String cuenta) {
        this(UUID.randomUUID(), nombre, cuenta);
    }

    private static String requerido(String valor, String campo) {
        Objects.requireNonNull(valor, campo + " es obligatorio");
        if (valor.isBlank()) throw new IllegalArgumentException(campo + " no puede estar vacío");
        return valor.trim();
    }
}
