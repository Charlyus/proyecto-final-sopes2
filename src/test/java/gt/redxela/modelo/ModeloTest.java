package gt.redxela.modelo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModeloTest {
    @Test void clienteYLineasValidanDatosObligatoriosYCantidades() {
        assertThrows(IllegalArgumentException.class, () -> new Cliente(" ", "ana@example.test"));
        assertThrows(IllegalArgumentException.class, () -> new LineaPedido(TipoMercancia.FRAGIL, 0));
    }

    @Test void pedidoAceptaVariasLineasYCalculaUnidades() {
        var pedido = new Pedido(new Cliente("Ana", "ana@example.test"), NivelServicio.EXPRES,
                List.of(new LineaPedido(TipoMercancia.DOCUMENTOS, 2),
                        new LineaPedido(TipoMercancia.FRAGIL, 3)));
        assertEquals(5, pedido.totalUnidades());
        assertEquals(EstadoPedido.RECIBIDO, pedido.estado());
        assertThrows(IllegalArgumentException.class,
                () -> new Pedido(new Cliente("Ana", "ana@example.test"), NivelServicio.EXPRES, List.of()));
    }

    @Test void catalogoContieneCincoTiposConRecursosYEstadosDeEsperaDistintos() {
        assertEquals(5, CatalogoMercancias.todos().size());
        assertNotEquals(CatalogoMercancias.requisitosDe(TipoMercancia.DOCUMENTOS),
                CatalogoMercancias.requisitosDe(TipoMercancia.VOLUMINOSO));
        assertNotEquals(EstadoPedido.ESPERANDO_STOCK, EstadoPedido.ESPERANDO_RECURSOS);
    }
}
