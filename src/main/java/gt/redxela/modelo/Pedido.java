package gt.redxela.modelo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pedido inmutable: el motor podrá administrar su ciclo de vida sin mutar sus líneas. */
public record Pedido(UUID id, Cliente cliente, NivelServicio nivelServicio,
                     List<LineaPedido> lineas, EstadoPedido estado, Instant creadoEn) {
    public Pedido {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(cliente, "cliente es obligatorio");
        Objects.requireNonNull(nivelServicio, "nivel de servicio es obligatorio");
        Objects.requireNonNull(lineas, "líneas son obligatorias");
        if (lineas.isEmpty()) throw new IllegalArgumentException("el pedido debe tener al menos una línea");
        if (lineas.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("las líneas no pueden ser nulas");
        lineas = List.copyOf(lineas);
        int total=0;
        for (LineaPedido linea : lineas) total=Math.addExact(total,linea.cantidad());
        Objects.requireNonNull(estado, "estado es obligatorio");
        Objects.requireNonNull(creadoEn, "fecha de creación es obligatoria");
    }

    public Pedido(Cliente cliente, NivelServicio nivelServicio, List<LineaPedido> lineas) {
        this(UUID.randomUUID(), cliente, nivelServicio, lineas, EstadoPedido.RECIBIDO, Instant.now());
    }

    public int totalUnidades() { return lineas.stream().mapToInt(LineaPedido::cantidad).reduce(0,Math::addExact); }
}
