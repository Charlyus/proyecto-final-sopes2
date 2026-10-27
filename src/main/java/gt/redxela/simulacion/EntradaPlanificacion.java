package gt.redxela.simulacion;

import java.util.UUID;
import gt.redxela.modelo.NivelServicio;

/** Vista de despacho evaluada toda al mismo instante monotónico. */
public record EntradaPlanificacion(UUID pedidoId, int prioridadEfectiva, int unidades,
                                   long esperaMillis, NivelServicio servicio) {
    public EntradaPlanificacion(UUID pedidoId,int prioridadEfectiva,int unidades,long esperaMillis) {
        this(pedidoId,prioridadEfectiva,unidades,esperaMillis,NivelServicio.ESTANDAR);
    }
}
