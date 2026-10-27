package gt.redxela.modelo;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Cuenta en memoria. Es inmutable para que los pedidos conserven su nivel contratado. */
public record Cliente(UUID id, String nombre, String correo, NivelServicio nivelServicio) {
    public Cliente {
        Objects.requireNonNull(id, "id es obligatorio");
        nombre = requerido(nombre, "nombre");
        correo = requerido(correo, "correo").toLowerCase(Locale.ROOT);
        if (!correo.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new IllegalArgumentException("correo no tiene un formato válido");
        Objects.requireNonNull(nivelServicio, "nivel de servicio es obligatorio");
    }
    public Cliente(String nombre, String correo) { this(UUID.randomUUID(), nombre, correo, NivelServicio.ESTANDAR); }
    public Cliente(String nombre, String correo, NivelServicio nivel) { this(UUID.randomUUID(), nombre, correo, nivel); }
    public Cliente(UUID id, String nombre, String correo) { this(id, nombre, correo, NivelServicio.ESTANDAR); }
    /** Alias de compatibilidad para código previo a cuentas con correo. */
    public String cuenta() { return correo; }
    public String correoNormalizado() { return correo; }
    @Override public String toString() { return nombre + " <" + correo + "> · " + nivelServicio.etiqueta(); }
    private static String requerido(String valor, String campo) {
        Objects.requireNonNull(valor, campo + " es obligatorio");
        if (valor.isBlank()) throw new IllegalArgumentException(campo + " no puede estar vacío");
        return valor.trim();
    }
}
