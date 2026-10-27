package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.*;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DemostracionDeadlockTest {
    private static final long LIMIT=3;
    private static final class Fixture implements AutoCloseable {
        final GestorRecursos resources=new GestorRecursos();
        final ArrayBlockingQueue<SolicitudRecepcion> queue=new ArrayBlockingQueue<>(4);
        final Almacen warehouse=new Almacen(List.of(100,100,100,100,100));
        final Recepcion reception=new Recepcion(queue,warehouse.capacidadTotal());
        final Inventario inventory=new Inventario(queue,reception,warehouse,resources);
        final MotorSimulacion motor=new MotorSimulacion(2,new TiempoSimulado(5_000),EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,1000,resources);
        final DemostracionDeadlock demo;
        Fixture() { this(2); }
        Fixture(long workMillis) { demo=new DemostracionDeadlock(resources,motor,reception,workMillis); motor.conectarInventario(inventory); motor.iniciar(); }
        void startAndWaitCycle() throws Exception {
            demo.iniciar();
            assertTrue(demo.esperarEstado(DemostracionDeadlock.Estado.CONFLICTO_CONFIRMADO,LIMIT,TimeUnit.SECONDS),demo.snapshot().toString());
        }
        @Override public void close() { demo.close(); motor.close(); }
    }

    @Test void cicloRealReproducibleUsaTodosLosMontacargasYNoModificaStock() throws Exception {
        try(Fixture f=new Fixture()) {
            var before=f.inventory.snapshot(); f.startAndWaitCycle();
            var snapshot=f.resources.snapshotCompleto();
            assertEquals(3,snapshot.ciclo().size()); assertEquals(snapshot.ciclo().get(0),snapshot.ciclo().get(2));
            assertEquals(0,snapshot.recursos().get(TipoRecurso.ESCANER).disponibles());
            assertEquals(0,snapshot.recursos().get(TipoRecurso.MONTACARGAS).disponibles());
            assertTrue(f.demo.snapshot().deadlockConfirmado());
            assertTrue(f.demo.resolver(DemostracionDeadlock.Operacion.A));
            assertFalse(f.demo.resolver(DemostracionDeadlock.Operacion.B));
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.COMPLETADA,LIMIT,TimeUnit.SECONDS));
            assertEquals(before,f.inventory.snapshot());
            assertTrue(f.demo.snapshot().eventos().stream().anyMatch(e->e.contains("quedó en espera")));
            assertTrue(f.demo.snapshot().eventos().stream().anyMatch(e->e.contains("reintentada")));
            assertTrue(f.resources.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
            assertFalse(f.resources.snapshotCompleto().modoDemostracionActivo());
            assertTrue(f.demo.esperarHilos(2,TimeUnit.SECONDS));
            assertTrue(f.resources.esperarSolicitud("verificacion-normal",10,TimeUnit.MILLISECONDS)==false);
            try(var normal=f.resources.adquirir("normal-restaurado",Map.of(TipoRecurso.ESCANER,1))) {
                assertEquals(0,f.resources.snapshot().get(TipoRecurso.ESCANER).disponibles());
            }
        }
    }

    @Test void resolverBPermiteLaMismaRecuperacionSinEfectosDuplicados() throws Exception {
        try(Fixture f=new Fixture()) {
            f.startAndWaitCycle();
            assertTrue(f.demo.resolver(DemostracionDeadlock.Operacion.B));
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.COMPLETADA,LIMIT,TimeUnit.SECONDS));
            assertEquals(DemostracionDeadlock.EstadoOperacion.COMPLETADA,f.demo.snapshot().operaciones().get(0).estado());
            assertEquals(DemostracionDeadlock.EstadoOperacion.COMPLETADA,f.demo.snapshot().operaciones().get(1).estado());
            assertTrue(f.demo.esperarHilos(2,TimeUnit.SECONDS));
        }
    }

    @Test void pedidosAceptadosQueEsperanStockSeConservanDuranteElAislamiento() throws Exception {
        try(Fixture f=new Fixture()) {
            Cliente client=new Cliente("En espera","stock@example.local",NivelServicio.ESTANDAR);
            Pedido accepted=new Pedido(client,NivelServicio.ESTANDAR,
                    List.of(new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"),1)));
            f.motor.enviar(accepted);
            assertTrue(f.motor.esperarEstado(accepted.id(),EstadoPedido.ESPERANDO_STOCK,LIMIT,TimeUnit.SECONDS));
            f.startAndWaitCycle(); assertTrue(f.demo.resolver(DemostracionDeadlock.Operacion.A));
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.COMPLETADA,LIMIT,TimeUnit.SECONDS));
            assertTrue(f.motor.snapshot().pedidos().stream().anyMatch(p->p.pedido().id().equals(accepted.id())
                    &&p.estado()==EstadoPedido.ESPERANDO_STOCK));
        }
    }

    @Test void esperaSimpleNoSeClasificaComoDeadlock() throws Exception {
        GestorRecursos manager=new GestorRecursos();
        try(var held=manager.adquirir("A",Map.of(TipoRecurso.ESCANER,1))) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<?> waiter=executor.submit(()->{ try(var ignored=manager.adquirir("B",Map.of(TipoRecurso.ESCANER,1))) { } catch(InterruptedException e){Thread.currentThread().interrupt();} });
                assertTrue(manager.esperarSolicitud("B",LIMIT,TimeUnit.SECONDS));
                var snapshot=manager.snapshotCompleto();
                assertTrue(snapshot.ciclo().isEmpty()); assertFalse(snapshot.modoDemostracionActivo());
                waiter.cancel(true);
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(LIMIT,TimeUnit.SECONDS)); }
        }
        assertTrue(manager.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
    }

    @Test void cancelarDuranteCicloLiberaTodoYRestauraModoNormal() throws Exception {
        try(Fixture f=new Fixture()) {
            f.startAndWaitCycle(); f.demo.cancelar();
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.CANCELADA,LIMIT,TimeUnit.SECONDS));
            assertTrue(f.demo.esperarHilos(2,TimeUnit.SECONDS));
            assertTrue(f.resources.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
            assertFalse(f.resources.snapshotCompleto().modoDemostracionActivo());
            assertTrue(f.motor.snapshot().activo());
        }
    }

    @Test void cancelarDurantePreparacionYRecuperacionLimpiaLasReservas() throws Exception {
        try(Fixture f=new Fixture(2_000)) {
            f.demo.iniciar(); f.demo.cancelar();
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.CANCELADA,LIMIT,TimeUnit.SECONDS));
            assertTrue(f.demo.esperarHilos(2,TimeUnit.SECONDS));
            assertTrue(f.resources.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
        }
        try(Fixture f=new Fixture(2_000)) {
            f.startAndWaitCycle(); assertTrue(f.demo.resolver(DemostracionDeadlock.Operacion.A));
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.RECUPERANDO,LIMIT,TimeUnit.SECONDS));
            f.demo.cancelar();
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.CANCELADA,LIMIT,TimeUnit.SECONDS));
            assertTrue(f.demo.esperarHilos(2,TimeUnit.SECONDS));
            assertTrue(f.resources.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
            assertFalse(f.resources.snapshotCompleto().modoDemostracionActivo());
        }
    }

    @Test void aislamientoRechazaTrabajoNormalEnCursoSinPausarElMotor() throws Exception {
        try(Fixture f=new Fixture()) {
            Cliente client=new Cliente("En curso","running@example.local",NivelServicio.ESTANDAR);
            Producto product=CatalogoProductos.porId("DOCUMENTOS");
            f.reception.recibir(product,1);
            assertEquals(EstadoRecepcion.APLICADA,f.reception.esperarEstado(f.reception.snapshot().get(0).id(),EstadoRecepcion.APLICADA,LIMIT,TimeUnit.SECONDS).estado());
            Pedido order=new Pedido(client,NivelServicio.ESTANDAR,List.of(new LineaPedido(product,1)));
            f.motor.enviar(order); assertTrue(f.motor.esperarEstado(order.id(),EstadoPedido.EN_PROCESAMIENTO,LIMIT,TimeUnit.SECONDS));
            f.demo.iniciar();
            assertTrue(f.demo.esperarEstado(DemostracionDeadlock.Estado.ERROR,LIMIT,TimeUnit.SECONDS));
            assertTrue(f.demo.snapshot().error().contains("pedidos normales en curso"));
            assertTrue(f.motor.snapshot().activo());
        }
    }
}
