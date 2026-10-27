package gt.redxela.simulacion;

import gt.redxela.modelo.*;

public record EstadoPedidoMotor(Pedido pedido, EstadoPedido estado, long llegadaNanos,
                                Long inicioNanos, Long finNanos, double progreso,
                                long tiempoRestanteEstimadoMillis, OrigenPedido origen) { }
