package gt.redxela.inventario;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.modelo.EstadoRecepcion;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
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
}
