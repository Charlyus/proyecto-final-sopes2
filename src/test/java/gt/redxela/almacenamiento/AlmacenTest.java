package gt.redxela.almacenamiento;

import gt.redxela.inventario.CatalogoProductos;
import gt.redxela.modelo.Producto;
import gt.redxela.modelo.TipoMercancia;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AlmacenTest {
    @Test void distribuyeLoteDeterministicamenteEnUbicacionesNoContiguas() {
        var warehouse=new Almacen(List.of(2,3,2,4,2));
        Producto docs=CatalogoProductos.porId("DOCUMENTOS");
        Producto fragile=CatalogoProductos.porId("FRAGIL");
        warehouse.registrarProducto(docs); warehouse.registrarProducto(fragile);
        warehouse.aplicar(warehouse.planificar("L-F",fragile,2),fragile);
        warehouse.aplicar(warehouse.planificar("L-D",docs,6),docs);
        assertEquals(List.of("UB-001","UB-003","UB-005"),warehouse.lotes().stream()
                .filter(p -> p.productoId().equals(docs.id())).map(Almacen.ParteLote::ubicacionId).toList());
        assertEquals(12,warehouse.volumenOcupado());
        assertTrue(warehouse.ubicaciones().stream().allMatch(u -> u.volumenUtilizado()<=u.capacidadVolumen()));
    }
    @Test void capacidadesNoPositivasSeRechazan() {
        assertThrows(IllegalArgumentException.class,()->new Almacen(List.of(3,0)));
    }

    @Test void validaSiExisteAsignacionSinMezclarProductosIndependienteDelOrdenGreedy() {
        var warehouse=new Almacen(List.of(10,6,6));
        var sixVolume=new Producto("A","Producto A",TipoMercancia.DOCUMENTOS,6);
        var threeVolume=new Producto("B","Producto B",TipoMercancia.FRAGIL,3);
        assertDoesNotThrow(() -> warehouse.validarCabeEnAlmacenVacio(Map.of("A",sixVolume,"B",threeVolume),
                Map.of("A",2,"B",3)));
    }
}
