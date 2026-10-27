package gt.redxela.concurrencia;

import gt.redxela.modelo.*;
import java.util.EnumMap;
import java.util.Map;

/** Una petición combinada por pedido: para cada clase se toma el máximo de sus líneas, no su suma. */
public final class NecesidadesPedido {
    private NecesidadesPedido() { }
    public static Map<TipoRecurso, Integer> calcular(Pedido pedido) {
        EnumMap<TipoRecurso, Integer> result = new EnumMap<>(TipoRecurso.class);
        for (LineaPedido linea : pedido.lineas()) {
            RequisitoRecursos r = CatalogoMercancias.requisitosDe(linea.mercancia());
            merge(result, TipoRecurso.EMPAQUE, r.empaques());
            merge(result, TipoRecurso.AREA_CARGA, r.areasCarga());
            merge(result, TipoRecurso.ENCARGADO, r.encargados());
            merge(result, TipoRecurso.MONTACARGAS, r.montacargas());
            merge(result, TipoRecurso.CALIDAD, r.estacionesCalidad());
            merge(result, TipoRecurso.ESCANER, r.escaneres());
        }
        result.entrySet().removeIf(e -> e.getValue() == 0);
        return Map.copyOf(result);
    }
    private static void merge(Map<TipoRecurso, Integer> needs, TipoRecurso type, int value) {
        needs.merge(type, value, Math::max);
    }
}
