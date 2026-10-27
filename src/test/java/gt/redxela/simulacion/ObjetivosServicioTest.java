package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.*;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ObjetivosServicioTest {
    @Test void escalaConvierteTiempoMonotonicoYValidaRango() {
        AtomicLong source=new AtomicLong(10_000);
        EscalaTiempoSimulado clock=new EscalaTiempoSimulado(60,source::get);
        source.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertEquals(TimeUnit.MINUTES.toNanos(2),clock.ahoraNanos());
        assertEquals(60,clock.factor());
        assertThrows(IllegalArgumentException.class,()->new EscalaTiempoSimulado(0));
        assertThrows(IllegalArgumentException.class,()->new EscalaTiempoSimulado(Double.NaN));
    }

    @Test void objetivosNumericosYExpresCualitativoCorrespondenAlEnunciado() {
        assertNull(NivelServicio.EXPRES.objetivoEsperaMillis());
        assertEquals("Casi inmediato (cualitativo)",NivelServicio.EXPRES.objetivoEsperaTexto());
        assertEquals(10*60_000L,NivelServicio.PRIORITARIO.objetivoEsperaMillis());
        assertEquals(30*60_000L,NivelServicio.ESTANDAR.objetivoEsperaMillis());
        assertEquals(60*60_000L,NivelServicio.PROGRAMADO.objetivoEsperaMillis());
        assertEquals(120*60_000L,NivelServicio.ECONOMICO.objetivoEsperaMillis());
    }

    @Test void resultadoDelObjetivoSeCapturaAlInicioYPermaneceEnSnapshot() throws Exception {
        long base=System.nanoTime(); AtomicLong fakeMonotonic=new AtomicLong(base);
        EscalaTiempoSimulado clock=new EscalaTiempoSimulado(1,fakeMonotonic::get);
        GestorRecursos resources=new GestorRecursos(); var queue=new ArrayBlockingQueue<SolicitudRecepcion>(4);
        var reception=new Recepcion(queue,500); var inventory=new Inventario(queue,reception,new Almacen(List.of(100,100,100,100,100)),resources);
        var motor=new MotorSimulacion(1,new TiempoSimulado(500),EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,1_000,
                resources,clock);
        GestorRecursos.Reserva scanner=null;
        try {
            motor.conectarInventario(inventory); motor.iniciar();
            var receipt=reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),2);
            assertEquals(EstadoRecepcion.APLICADA,reception.esperarEstado(receipt.id(),EstadoRecepcion.APLICADA,3,TimeUnit.SECONDS).estado());
            scanner=resources.adquirir("prueba-retiene-escaner",Map.of(TipoRecurso.ESCANER,1));
            var order=new Pedido(new Cliente("Auditoría","sla@example.test",NivelServicio.PRIORITARIO),NivelServicio.PRIORITARIO,
                    List.of(new LineaPedido(TipoMercancia.DOCUMENTOS,1)));
            motor.enviar(order);
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.ESPERANDO_RECURSOS,3,TimeUnit.SECONDS));
            fakeMonotonic.set(base+TimeUnit.MINUTES.toNanos(10));
            scanner.close(); scanner=null;
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.EN_PROCESAMIENTO,3,TimeUnit.SECONDS));
            var started=motor.snapshot().pedidos().get(0);
            assertEquals(10*60_000L,started.esperaServicioMillis());
            assertEquals(EstadoPedidoMotor.ResultadoObjetivo.DENTRO,started.resultadoObjetivo());
            fakeMonotonic.addAndGet(TimeUnit.MINUTES.toNanos(15));
            var later=motor.snapshot().pedidos().get(0);
            assertEquals(EstadoPedidoMotor.ResultadoObjetivo.DENTRO,later.resultadoObjetivo());
            assertEquals(10*60_000L,later.esperaServicioMillis());
            assertTrue(motor.esperarEstado(order.id(),EstadoPedido.FINALIZADO,3,TimeUnit.SECONDS));

            scanner=resources.adquirir("prueba-retiene-escaner-2",Map.of(TipoRecurso.ESCANER,1));
            var lateOrder=new Pedido(new Cliente("Auditoría","sla2@example.test",NivelServicio.PRIORITARIO),NivelServicio.PRIORITARIO,
                    List.of(new LineaPedido(TipoMercancia.DOCUMENTOS,1)));
            motor.enviar(lateOrder);
            assertTrue(motor.esperarEstado(lateOrder.id(),EstadoPedido.ESPERANDO_RECURSOS,3,TimeUnit.SECONDS));
            fakeMonotonic.addAndGet(TimeUnit.MINUTES.toNanos(10)+TimeUnit.MILLISECONDS.toNanos(1));
            scanner.close(); scanner=null;
            assertTrue(motor.esperarEstado(lateOrder.id(),EstadoPedido.EN_PROCESAMIENTO,3,TimeUnit.SECONDS));
            var exceeded=motor.snapshot().pedidos().stream().filter(p->p.pedido().id().equals(lateOrder.id())).findFirst().orElseThrow();
            assertEquals(EstadoPedidoMotor.ResultadoObjetivo.EXCEDIDO,exceeded.resultadoObjetivo());
            assertEquals(10*60_000L+1,exceeded.esperaServicioMillis());
        } finally {
            if(scanner!=null) scanner.close(); motor.close();
        }
        assertTrue(motor.cierreCompleto());
        assertTrue(resources.snapshot().values().stream().allMatch(r->r.disponibles()==r.capacidad()));
    }
}
