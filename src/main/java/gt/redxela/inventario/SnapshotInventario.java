package gt.redxela.inventario;

import gt.redxela.almacenamiento.Almacen;
import java.util.List;

public record SnapshotInventario(List<EstadoStock> stock, List<Almacen.Ubicacion> ubicaciones,
                                 List<Almacen.ParteLote> lotes, int reservasActivas,
                                 int recepcionesPendientesEspacio) {
    public SnapshotInventario {
        stock=List.copyOf(stock); ubicaciones=List.copyOf(ubicaciones); lotes=List.copyOf(lotes);
    }
}
