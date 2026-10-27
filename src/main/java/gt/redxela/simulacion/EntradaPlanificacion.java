package gt.redxela.simulacion;

import java.util.UUID;

/** Vista de despacho evaluada toda al mismo instante monotónico. */
public record EntradaPlanificacion(UUID pedidoId, int prioridadEfectiva, int unidades,
                                   long esperaMillis) { }
