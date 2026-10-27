package gt.redxela.inventario;

import gt.redxela.modelo.Producto;
import java.util.Objects;
import java.util.UUID;

public record SolicitudRecepcion(UUID id, Producto producto, int cantidad, long llegadaNanos) {
    public SolicitudRecepcion {
        Objects.requireNonNull(id); Objects.requireNonNull(producto);
        if (cantidad <= 0) throw new IllegalArgumentException("cantidad debe ser positiva");
    }
}
