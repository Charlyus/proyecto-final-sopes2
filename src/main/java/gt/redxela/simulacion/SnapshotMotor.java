package gt.redxela.simulacion;

import java.util.List;

public record SnapshotMotor(List<EstadoPedidoMotor> pedidos, EstadisticasMotor estadisticas,
                            boolean activo) {
    public SnapshotMotor { pedidos = List.copyOf(pedidos); }
}
