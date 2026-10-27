package gt.redxela.modelo;

import java.util.Objects;

public record LineaPedido(String productoId, TipoMercancia mercancia, int cantidad) {
    public LineaPedido {
        Objects.requireNonNull(productoId, "producto es obligatorio");
        if (productoId.isBlank()) throw new IllegalArgumentException("producto no puede estar vacío");
        productoId = productoId.trim();
        Objects.requireNonNull(mercancia, "mercancía es obligatoria");
        if (!productoId.equals(mercancia.name()))
            throw new IllegalArgumentException("el producto no corresponde al tipo de mercancía");
        if (cantidad <= 0) throw new IllegalArgumentException("cantidad debe ser positiva");
    }

    /** Compatibilidad con pedidos de etapas anteriores: el ID canónico es el nombre del tipo. */
    public LineaPedido(TipoMercancia mercancia, int cantidad) {
        this(Objects.requireNonNull(mercancia).name(), mercancia, cantidad);
    }

    public LineaPedido(Producto producto, int cantidad) {
        this(Objects.requireNonNull(producto).id(), producto.tipo(), cantidad);
    }
}
