package gt.redxela.simulacion;

import gt.redxela.concurrencia.*;
import gt.redxela.almacenamiento.Almacen;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MotorSimulacionTest {
    private GestorRecursos resources;
    private Inventario inventory(int amount) {
        var queue=new ArrayBlockingQueue<SolicitudRecepcion>(8);
        var reception=new Recepcion(queue,2000);
        resources=new GestorRecursos();
        var inventory=new Inventario(queue,reception,new Almacen(List.of(500,500,500,500)),resources);
        reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),amount);
        return inventory;
    }
    private Pedido pedido(int unidades) {
        return new Pedido(new Cliente("Prueba", "prueba@example.test"), NivelServicio.ESTANDAR,
                List.of(new LineaPedido(TipoMercancia.DOCUMENTOS, unidades)));
    }
    private record SystemParts(GestorRecursos resources, Recepcion reception, Inventario inventory) { }
    private SystemParts system(int queueSize) {
        var resources=new GestorRecursos(); var queue=new ArrayBlockingQueue<SolicitudRecepcion>(queueSize);
        var reception=new Recepcion(queue,2000);
        var inventory=new Inventario(queue,reception,new Almacen(List.of(500,500,500,500)),resources);
        return new SystemParts(resources,reception,inventory);
    }
    @Test void pedidosSeEjecutanUnaVezYCloseCancelaTrabajoYLiberaRecursos() throws Exception {
        var inventory=inventory(20);
        var motor = new MotorSimulacion(1, new TiempoSimulado(10_000),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 100,resources);
        motor.conectarInventario(inventory);
        try {
            motor.iniciar(); Pedido a=pedido(10); Pedido b=pedido(1);
            motor.enviar(a); motor.enviar(b);
            assertTrue(motor.esperarEstado(a.id(),EstadoPedido.EN_PROCESAMIENTO,2,TimeUnit.SECONDS));
            assertEquals(1, motor.snapshot().estadisticas().procesandose());
        } finally { motor.close(); }
        var snapshot=motor.snapshot();
        assertTrue(motor.cierreCompleto());
        assertTrue(snapshot.pedidos().stream().noneMatch(p -> p.estado()==EstadoPedido.EN_PROCESAMIENTO));
        assertTrue(snapshot.pedidos().stream().anyMatch(p -> p.estado()==EstadoPedido.CANCELADO));
        assertTrue(snapshot.estadisticas().recursos().values().stream().allMatch(r -> r.disponibles()==r.capacidad()));
        assertEquals(2, snapshot.pedidos().size());
    }
    @Test void rechazaCapacidadYIdsDuplicados() {
        var inventory=inventory(5);
        var motor = new MotorSimulacion(1, new TiempoSimulado(1), EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 100,resources);
        motor.conectarInventario(inventory);
        try {
            motor.iniciar(); var p=pedido(1); motor.enviar(p);
            assertThrows(IllegalArgumentException.class, () -> motor.enviar(p));
        } finally { motor.close(); }
    }

    @Test void rechazaPedidoQueNoCabeEnAlmacenVacioIncluyendoLineasRepetidas() {
        var parts=smallWarehouseSystem(); var motor=new MotorSimulacion(1,new TiempoSimulado(1),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,100,parts.resources());
        try {
            motor.conectarInventario(parts.inventory()); motor.iniciar();
            var oversized=pedido(501);
            var error=assertThrows(IllegalArgumentException.class,() -> motor.enviar(oversized));
            assertTrue(error.getMessage().contains("no cabe en el almacén vacío"));
            assertTrue(motor.snapshot().pedidos().isEmpty());

            // El volumen total suma exactamente 500, pero no cabe: documentos y
            // voluminosos no pueden compartir ubicación y sus unidades no llenan bins exactos.
            var fragmented=new Pedido(new Cliente("Prueba","fragmentado@example.test"),NivelServicio.ESTANDAR,List.of(
                    new LineaPedido(TipoMercancia.DOCUMENTOS,396),
                    new LineaPedido(TipoMercancia.VOLUMINOSO,13)));
            assertTrue(assertThrows(IllegalArgumentException.class,() -> motor.enviar(fragmented))
                    .getMessage().contains("una mercancía por ubicación"));
            var repeated=new Pedido(new Cliente("Prueba","lineas@example.test"),NivelServicio.ESTANDAR,List.of(
                    new LineaPedido(TipoMercancia.DOCUMENTOS,300),new LineaPedido(TipoMercancia.DOCUMENTOS,300)));
            assertThrows(IllegalArgumentException.class,() -> motor.enviar(repeated));
            assertTrue(motor.snapshot().pedidos().isEmpty());
        } finally { motor.close(); }
    }

    @Test void pedidoQueCabePeroNoTieneStockQuedaEnEsperaTemporal() throws Exception {
        var parts=smallWarehouseSystem(); var motor=new MotorSimulacion(1,new TiempoSimulado(1),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,100,parts.resources());
        try {
            motor.conectarInventario(parts.inventory()); motor.iniciar();
            var valid=pedido(500);
            motor.enviar(valid);
            assertTrue(motor.esperarEstado(valid.id(),EstadoPedido.ESPERANDO_STOCK,1,TimeUnit.SECONDS));
            assertEquals(1,motor.snapshot().pedidos().size());
            assertEquals(1,motor.snapshot().estadisticas().esperandoStock());
        } finally { motor.close(); }
        assertTrue(motor.cierreCompleto());
    }

    private SystemParts smallWarehouseSystem() {
        var manager=new GestorRecursos(); var queue=new ArrayBlockingQueue<SolicitudRecepcion>(8);
        var reception=new Recepcion(queue,500);
        var inventory=new Inventario(queue,reception,new Almacen(List.of(100,100,100,100,100)),manager);
        return new SystemParts(manager,reception,inventory);
    }

    @Test void llegadaDeMercaderiaDesbloqueaPedidoEsperandoStock() throws Exception {
        var parts=system(8); var motor=new MotorSimulacion(1,new TiempoSimulado(1000),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,100,parts.resources());
        try {
            motor.conectarInventario(parts.inventory()); motor.iniciar();
            var order=new Pedido(new Cliente("Prueba","prueba@example.test"),NivelServicio.ESTANDAR,List.of(
                    new LineaPedido(TipoMercancia.DOCUMENTOS,1),new LineaPedido(TipoMercancia.DOCUMENTOS,1)));
            motor.enviar(order);
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.ESPERANDO_STOCK,1,TimeUnit.SECONDS));
            var receipt=parts.reception().recibir(CatalogoProductos.porId("DOCUMENTOS"),1);
            assertEquals(gt.redxela.modelo.EstadoRecepcion.APLICADA,
                    parts.reception().esperarEstado(receipt.id(),gt.redxela.modelo.EstadoRecepcion.APLICADA,
                            2,TimeUnit.SECONDS).estado());
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.ESPERANDO_STOCK,1,TimeUnit.SECONDS));
            var receipt2=parts.reception().recibir(CatalogoProductos.porId("DOCUMENTOS"),1);
            assertEquals(gt.redxela.modelo.EstadoRecepcion.APLICADA,
                    parts.reception().esperarEstado(receipt2.id(),gt.redxela.modelo.EstadoRecepcion.APLICADA,
                            2,TimeUnit.SECONDS).estado());
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.EN_PROCESAMIENTO,2,TimeUnit.SECONDS));
            assertEquals(0,parts.inventory().snapshot().stock().get(0).fisico());
        } finally { motor.close(); }
        assertTrue(motor.cierreCompleto());
    }

    @Test void cancelarAntesDeConsumirDevuelveReservaYLiberaRecursosParciales() throws Exception {
        var parts=system(8); var motor=new MotorSimulacion(1,new TiempoSimulado(10_000),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,100,parts.resources());
        GestorRecursos.Reserva scanner=null;
        try {
            motor.conectarInventario(parts.inventory());
            var receipt=parts.reception().recibir(CatalogoProductos.porId("DOCUMENTOS"),1);
            motor.iniciar();
            assertEquals(gt.redxela.modelo.EstadoRecepcion.APLICADA,
                    parts.reception().esperarEstado(receipt.id(),gt.redxela.modelo.EstadoRecepcion.APLICADA,
                            2,TimeUnit.SECONDS).estado());
            scanner=parts.resources().adquirir("prueba-bloquea-escaner",java.util.Map.of(TipoRecurso.ESCANER,1));
            var order=pedido(1); motor.enviar(order);
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.ESPERANDO_RECURSOS,2,TimeUnit.SECONDS));
            assertEquals(0,parts.inventory().snapshot().stock().get(0).disponible());
            assertTrue(motor.cancelar(order.id()));
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.CANCELADO,1,TimeUnit.SECONDS));
            assertEquals(1,parts.inventory().snapshot().stock().get(0).disponible());
        } finally { if(scanner!=null) scanner.close(); motor.close(); }
        assertTrue(motor.cierreCompleto());
        assertTrue(parts.resources().snapshot().values().stream().allMatch(r -> r.disponibles()==r.capacidad()));
    }
}
