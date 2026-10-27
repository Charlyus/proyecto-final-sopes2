package gt.redxela.inventario;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.modelo.EstadoRecepcion;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class InventarioTest {
    private record Setup(BlockingQueue<SolicitudRecepcion> queue, Recepcion reception, Inventario inventory) { }
    private Setup setup(int queueSize,int... capacities) {
        var queue=new ArrayBlockingQueue<SolicitudRecepcion>(queueSize);
        long total=java.util.Arrays.stream(capacities).sum();
        var reception=new Recepcion(queue,total);
        var inventory=new Inventario(queue,reception,new Almacen(java.util.Arrays.stream(capacities).boxed().toList()),new GestorRecursos());
        return new Setup(queue,reception,inventory);
    }
    @Test void sinEspacioRecepcionEsPendienteYSeAplicaAlLiberarUbicacion() throws Exception {
        var s=setup(8,10); var docs=CatalogoProductos.porId("DOCUMENTOS");
        try {
            s.inventory.iniciar();
            var first=s.reception.recibir(docs,10);
            assertEquals(EstadoRecepcion.APLICADA,s.reception.esperarEstado(first.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            var pending=s.reception.recibir(docs,2);
            assertEquals(EstadoRecepcion.PENDIENTE_ESPACIO,s.reception.esperarEstado(pending.id(),EstadoRecepcion.PENDIENTE_ESPACIO,2,TimeUnit.SECONDS).estado());
            var order=UUID.randomUUID(); assertTrue(s.inventory.reservar(order,java.util.Map.of(docs.id(),5)));
            s.inventory.consumirReserva(order);
            assertEquals(0,s.inventory.snapshot().recepcionesPendientesEspacio());
            assertEquals(7,s.inventory.snapshot().stock().get(0).fisico());
            assertEquals(s.inventory.snapshot().stock().get(0).fisico(),
                    s.inventory.snapshot().lotes().stream().mapToInt(Almacen.ParteLote::cantidad).sum());
        } finally { s.inventory.close(); }
    }

    @Test void recepcionImposibleYColaLlenaSonRechazosExplicitos() {
        var s=setup(1,10);
        var first=s.reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),1);
        var overflow=s.reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),11);
        var saturated=s.reception.recibir(CatalogoProductos.porId("FRAGIL"),1);
        assertEquals(EstadoRecepcion.EN_COLA,first.estado());
        assertEquals(EstadoRecepcion.RECHAZADA,overflow.estado());
        assertTrue(overflow.motivo().contains("capacidad total"));
        assertEquals(EstadoRecepcion.RECHAZADA_COLA_LLENA,saturated.estado());
        assertEquals(0,s.inventory.snapshot().stock().stream().mapToInt(EstadoStock::fisico).sum());
        s.inventory.close();
    }

    @Test void reservaMultilineaEsAtomicaYConcurrenteSinSobreventa() throws Exception {
        var s=setup(8,100); var docs=CatalogoProductos.porId("DOCUMENTOS"); var fragile=CatalogoProductos.porId("FRAGIL");
        try {
            s.inventory.iniciar();
            var a=s.reception.recibir(docs,2); var b=s.reception.recibir(fragile,2);
            s.reception.esperarEstado(a.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS);
            s.reception.esperarEstado(b.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS);
            assertFalse(s.inventory.reservar(UUID.randomUUID(),java.util.Map.of(docs.id(),2,fragile.id(),3)));
            assertEquals(2,s.inventory.snapshot().stock().get(0).disponible());
            var gate=new CountDownLatch(1); var done=new CountDownLatch(2); var successes=new java.util.concurrent.atomic.AtomicInteger();
            var executor=Executors.newFixedThreadPool(2);
            try {
                for(int i=0;i<2;i++) executor.submit(()->{ try { gate.await(); if(s.inventory.reservar(UUID.randomUUID(),java.util.Map.of(docs.id(),2))) successes.incrementAndGet(); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
                gate.countDown(); assertTrue(done.await(2,TimeUnit.SECONDS));
                assertEquals(1,successes.get());
                assertEquals(0,s.inventory.snapshot().stock().get(0).disponible());
            } finally { executor.shutdownNow(); }
        } finally { s.inventory.close(); }
    }

    @Test void mensajeDuplicadoNoSeAplicaDosVeces() throws Exception {
        var s=setup(4,20); var seen=new CountDownLatch(1); s.inventory.alCambiarStock(seen::countDown);
        var id=UUID.randomUUID(); var msg=new SolicitudRecepcion(id,CatalogoProductos.porId("DOCUMENTOS"),2,System.nanoTime());
        assertTrue(s.queue.offer(msg)); assertTrue(s.queue.offer(msg));
        try {
            s.inventory.iniciar(); assertTrue(seen.await(2,TimeUnit.SECONDS));
            assertEquals(2,s.inventory.snapshot().stock().get(0).fisico());
        } finally { s.inventory.close(); }
    }

    @Test void recepcionPendienteNoImpideAplicarMensajesPosterioresQueSiCaben() throws Exception {
        var s=setup(8,10); var docs=CatalogoProductos.porId("DOCUMENTOS");
        try {
            s.inventory.iniciar();
            var base=s.reception.recibir(docs,8);
            s.reception.esperarEstado(base.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS);
            var blocked=s.reception.recibir(CatalogoProductos.porId("VOLUMINOSO"),1);
            assertEquals(EstadoRecepcion.PENDIENTE_ESPACIO,
                    s.reception.esperarEstado(blocked.id(),EstadoRecepcion.PENDIENTE_ESPACIO,2,TimeUnit.SECONDS).estado());
            var fits=s.reception.recibir(docs,2);
            assertEquals(EstadoRecepcion.APLICADA,
                    s.reception.esperarEstado(fits.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            assertEquals(EstadoRecepcion.PENDIENTE_ESPACIO,s.reception.estado(blocked.id()).estado());
            assertEquals(10,s.inventory.snapshot().stock().get(0).fisico());
        } finally { s.inventory.close(); }
    }

    @Test void cierreDejaMensajesNoConsumidosEnColaYNoLosMarcaAplicados() {
        var s=setup(4,20);
        var accepted=s.reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),3);
        assertEquals(1,s.queue.size());
        s.inventory.close();
        assertEquals(1,s.queue.size());
        assertEquals(EstadoRecepcion.EN_COLA,s.reception.estado(accepted.id()).estado());
        assertTrue(s.inventory.consumidorTerminado());
    }

    @Test void reasignacionDeStockRespetaElOrdenQueEntregaElPlanificador() throws Exception {
        var s=setup(4,20); var docs=CatalogoProductos.porId("DOCUMENTOS");
        try {
            s.inventory.iniciar(); var receipt=s.reception.recibir(docs,1);
            s.reception.esperarEstado(receipt.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS);
            UUID economic=UUID.randomUUID(), express=UUID.randomUUID();
            var low=new Inventario.SolicitudReserva(economic,java.util.Map.of(docs.id(),1));
            var high=new Inventario.SolicitudReserva(express,java.util.Map.of(docs.id(),1));
            assertEquals(java.util.Set.of(economic),s.inventory.reasignarReservas(List.of(low,high)));
            assertEquals(java.util.Set.of(express),s.inventory.reasignarReservas(List.of(high,low)));
            assertEquals(0,s.inventory.snapshot().stock().get(0).disponible());
        } finally { s.inventory.close(); }
    }

    @Test void cantidadesLotesYVolumenesCoincidenConElEstadoObservado() throws Exception {
        var s=setup(12,100,100,100,100,100);
        try {
            s.inventory.iniciar();
            for (var input:List.of(new Object[]{"VOLUMINOSO",20},
                    new Object[]{"REFRIGERADO",6}, new Object[]{"PELIGROSO",12}, new Object[]{"FRAGIL",13})) {
                var request=s.reception.recibir(CatalogoProductos.porId((String)input[0]),(int)input[1]);
                assertEquals(EstadoRecepcion.APLICADA,s.reception.esperarEstado(request.id(),
                        EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            }

            UUID priorOrder=UUID.randomUUID();
            assertTrue(s.inventory.reservar(priorOrder,Map.of("VOLUMINOSO",1)));
            s.inventory.consumirReserva(priorOrder);

            var snapshot=s.inventory.snapshot();
            var stock=snapshot.stock().stream().collect(Collectors.toMap(EstadoStock::productoId,EstadoStock::fisico));
            assertEquals(0,stock.get("DOCUMENTOS"));
            assertEquals(19,stock.get("VOLUMINOSO")); assertEquals(6,stock.get("REFRIGERADO"));
            assertEquals(12,stock.get("PELIGROSO")); assertEquals(13,stock.get("FRAGIL"));
            assertEquals(Map.of("UB-001",88,"UB-002",64,"UB-003",24,"UB-004",60,"UB-005",39),
                    snapshot.ubicaciones().stream().collect(Collectors.toMap(Almacen.Ubicacion::id,Almacen.Ubicacion::volumenUtilizado)));
            var lotQuantities=snapshot.lotes().stream().collect(Collectors.groupingBy(Almacen.ParteLote::productoId,
                    Collectors.summingInt(Almacen.ParteLote::cantidad)));
            stock.forEach((id,quantity) -> assertEquals(quantity,lotQuantities.getOrDefault(id,0)));
            var volumes=snapshot.lotes().stream().collect(Collectors.groupingBy(Almacen.ParteLote::ubicacionId,
                    Collectors.summingInt(part -> part.cantidad()*CatalogoProductos.porId(part.productoId()).volumenPorUnidad())));
            snapshot.ubicaciones().forEach(location -> assertEquals(location.volumenUtilizado(),volumes.getOrDefault(location.id(),0)));
        } finally { s.inventory.close(); }
    }

    @Test void recepcionesAprovechanTodasLasUbicacionesCompatiblesYLaPendienteSeReevaluaAlLiberarUna() throws Exception {
        var s=setup(12,100,100,100,100,100); var bulky=CatalogoProductos.porId("VOLUMINOSO");
        try {
            s.inventory.iniciar();
            for (var input:List.of(new Object[]{"VOLUMINOSO",20},
                    new Object[]{"REFRIGERADO",6}, new Object[]{"PELIGROSO",12}, new Object[]{"FRAGIL",13})) {
                var request=s.reception.recibir(CatalogoProductos.porId((String)input[0]),(int)input[1]);
                assertEquals(EstadoRecepcion.APLICADA,s.reception.esperarEstado(request.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            }
            UUID priorOrder=UUID.randomUUID();
            assertTrue(s.inventory.reservar(priorOrder,Map.of(bulky.id(),1)));
            s.inventory.consumirReserva(priorOrder);

            var processed=new CountDownLatch(2);
            s.inventory.alCambiarStock(processed::countDown);
            var blocked=s.reception.recibir(bulky,6);
            assertEquals(EstadoRecepcion.PENDIENTE_ESPACIO,
                    s.reception.esperarEstado(blocked.id(),EstadoRecepcion.PENDIENTE_ESPACIO,2,TimeUnit.SECONDS).estado());
            assertTrue(s.reception.estado(blocked.id()).motivo().contains("volumen libre total 225"));
            assertTrue(s.reception.estado(blocked.id()).motivo().contains("volumen libre compatible 48"));

            // La planificación suma capacidad compatible de UB-001 y UB-002; la recepción
            // de cinco unidades se aplica repartida entre ambas pese a la solicitud anterior.
            var fitting=s.reception.recibir(bulky,5);
            assertEquals(EstadoRecepcion.APLICADA,
                    s.reception.esperarEstado(fitting.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            assertTrue(processed.await(2,TimeUnit.SECONDS));
            assertEquals(24,s.inventory.snapshot().stock().stream().filter(x -> x.productoId().equals(bulky.id())).findFirst().orElseThrow().fisico());
            assertEquals(Map.of("UB-001",1,"UB-002",4),s.inventory.snapshot().lotes().stream()
                    .filter(part -> part.loteId().equals(fitting.id().toString()))
                    .collect(Collectors.toMap(Almacen.ParteLote::ubicacionId,Almacen.ParteLote::cantidad)));
            assertTrue(s.reception.estado(blocked.id()).motivo().contains("volumen libre total 185"), s.reception.estado(blocked.id()).motivo());
            assertTrue(s.reception.estado(blocked.id()).motivo().contains("volumen libre compatible 8"), s.reception.estado(blocked.id()).motivo());
            assertTrue(s.reception.estado(blocked.id()).motivo().contains("caben 0 de 6"));

            UUID consume=UUID.randomUUID(); assertTrue(s.inventory.reservar(consume,Map.of(bulky.id(),20)));
            s.inventory.consumirReserva(consume);
            assertEquals(EstadoRecepcion.APLICADA,
                    s.reception.esperarEstado(blocked.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            var snapshot=s.inventory.snapshot();
            assertEquals(10,snapshot.stock().stream().filter(x -> x.productoId().equals(bulky.id())).findFirst().orElseThrow().fisico());
            assertEquals(80,snapshot.ubicaciones().stream().filter(x -> x.productoId().equals(bulky.id()))
                    .mapToInt(Almacen.Ubicacion::volumenUtilizado).sum());
            assertTrue(snapshot.lotes().stream().anyMatch(part -> part.loteId().equals(blocked.id().toString())
                    && part.ubicacionId().equals("UB-001")));
            assertEquals(snapshot.stock().stream().mapToInt(EstadoStock::fisico).sum(),
                    snapshot.lotes().stream().mapToInt(Almacen.ParteLote::cantidad).sum());
        } finally { s.inventory.close(); }
    }

    @Test void disponibilidadPorPedidoIncluyeSuReservaYExponeFaltantesDeOtrosPedidos() throws Exception {
        var s=setup(4,20); var docs=CatalogoProductos.porId("DOCUMENTOS");
        try {
            s.inventory.iniciar();
            var receipt=s.reception.recibir(docs,10);
            assertEquals(EstadoRecepcion.APLICADA,
                    s.reception.esperarEstado(receipt.id(),EstadoRecepcion.APLICADA,2,TimeUnit.SECONDS).estado());
            UUID first=UUID.randomUUID(), second=UUID.randomUUID();
            assertTrue(s.inventory.reservar(first,Map.of(docs.id(),6)));
            assertFalse(s.inventory.reservar(second,Map.of(docs.id(),6)));
            assertEquals(new Inventario.DisponibilidadLinea(6,6,0),
                    s.inventory.disponibilidadPedido(first,Map.of(docs.id(),6)).get(docs.id()));
            assertEquals(new Inventario.DisponibilidadLinea(6,4,2),
                    s.inventory.disponibilidadPedido(second,Map.of(docs.id(),6)).get(docs.id()));
            s.inventory.consumirReserva(first);
            assertEquals(new Inventario.DisponibilidadLinea(6,6,0),
                    s.inventory.disponibilidadPedido(first,Map.of(docs.id(),6)).get(docs.id()));
        } finally { s.inventory.close(); }
    }

}
