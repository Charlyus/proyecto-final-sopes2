package gt.redxela.inventario;

import gt.redxela.modelo.EstadoRecepcion;
import java.util.UUID;

public record EstadoRecepcionVista(UUID id, String productoId, String productoNombre,
                                   int cantidad, EstadoRecepcion estado, String motivo) { }
