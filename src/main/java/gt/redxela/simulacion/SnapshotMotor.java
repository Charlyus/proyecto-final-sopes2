package gt.redxela.simulacion;

import java.util.List;
import gt.redxela.planificacion.EstrategiaPlanificacion;

public record SnapshotMotor(List<EstadoPedidoMotor> pedidos, EstadisticasMotor estadisticas,
                            boolean activo, EstrategiaPlanificacion estrategia,
                            List<EntradaPlanificacion> colaDespacho, long evaluadoNanos) {
    public SnapshotMotor { pedidos = List.copyOf(pedidos); colaDespacho = List.copyOf(colaDespacho); }
}
