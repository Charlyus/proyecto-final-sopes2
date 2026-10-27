package gt.redxela.simulacion;

import gt.redxela.modelo.*;

public record EstadoPedidoMotor(Pedido pedido, EstadoPedido estado, long llegadaNanos,
                                Long inicioNanos, Long finNanos, double progreso,
                                long tiempoRestanteEstimadoMillis, OrigenPedido origen, String error,
                                long esperaServicioMillis, Long objetivoEsperaMillis,
                                ResultadoObjetivo resultadoObjetivo) {
    public enum ResultadoObjetivo { ESPERANDO, DENTRO, EXCEDIDO, CUALITATIVO }

    public EstadoPedidoMotor(Pedido pedido, EstadoPedido estado, long llegadaNanos,
                             Long inicioNanos, Long finNanos, double progreso,
                             long tiempoRestanteEstimadoMillis, OrigenPedido origen) {
        this(pedido, estado, llegadaNanos, inicioNanos, finNanos, progreso,
                tiempoRestanteEstimadoMillis, origen, null, 0,
                pedido.nivelServicio().objetivoEsperaMillis(), ResultadoObjetivo.ESPERANDO);
    }

    public EstadoPedidoMotor(Pedido pedido, EstadoPedido estado, long llegadaNanos,
                             Long inicioNanos, Long finNanos, double progreso,
                             long tiempoRestanteEstimadoMillis, OrigenPedido origen, String error) {
        this(pedido, estado, llegadaNanos, inicioNanos, finNanos, progreso,
                tiempoRestanteEstimadoMillis, origen, error, 0,
                pedido.nivelServicio().objetivoEsperaMillis(), ResultadoObjetivo.ESPERANDO);
    }
}
