package gt.redxela.modelo;

import java.util.Objects;

public record Producto(String id, String nombre, TipoMercancia tipo, int volumenPorUnidad) {
    public Producto {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(nombre, "nombre es obligatorio");
        Objects.requireNonNull(tipo, "tipo es obligatorio");
        if (id.isBlank() || nombre.isBlank()) throw new IllegalArgumentException("id y nombre son obligatorios");
        id = id.trim(); nombre = nombre.trim();
        if (volumenPorUnidad <= 0) throw new IllegalArgumentException("volumen debe ser positivo");
    }
    @Override public String toString() { return nombre; }
}
