package gt.redxela.planificacion;

import gt.redxela.modelo.Pedido;
import java.util.*;
import java.util.function.ToLongFunction;

/** Selección reevaluada en cada despacho; el envejecimiento añade un nivel cada intervalo. */
public final class PlanificadorPedidos {
    public record Candidato<T>(T valor, int prioridad, int unidades, long llegadaNanos, long orden) { }
    private final long intervaloEnvejecimientoNanos;
    public PlanificadorPedidos(long intervaloEnvejecimientoNanos) {
        if (intervaloEnvejecimientoNanos <= 0) throw new IllegalArgumentException("intervalo debe ser positivo");
        this.intervaloEnvejecimientoNanos = intervaloEnvejecimientoNanos;
    }
    public <T> T seleccionar(List<Candidato<T>> candidatos, EstrategiaPlanificacion estrategia, long ahoraNanos) {
        if (candidatos.isEmpty()) return null;
        Comparator<Candidato<T>> comparator = switch (estrategia) {
            case PRIORIDAD_ESTRICTA -> Comparator.comparingInt(Candidato<T>::prioridad).reversed()
                    .thenComparingLong(Candidato::orden);
            case PRIORIDAD_CON_ENVEJECIMIENTO -> Comparator
                    .comparingLong((Candidato<T> c) -> (long)c.prioridad + Math.max(0, ahoraNanos - c.llegadaNanos) / intervaloEnvejecimientoNanos).reversed()
                    .thenComparingLong(Candidato::orden);
            case MENOR_CANTIDAD_PRIMERO -> Comparator.comparingInt(Candidato<T>::unidades)
                    .thenComparingLong(Candidato::orden);
        };
        return candidatos.stream().min(comparator).orElseThrow().valor();
    }
}
