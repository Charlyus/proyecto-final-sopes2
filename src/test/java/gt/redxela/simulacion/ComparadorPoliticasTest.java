package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.concurrencia.TipoRecurso;
import gt.redxela.concurrencia.TiempoSimulado;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ComparadorPoliticasTest {
    private static final long LIMIT=15;

    @Test void scenarioSeedBuildsIdenticalInputsAndWarehouseFeasibleSufficientStock() {
        var profile=EscenarioComparacion.PERFILES.get(1);
        var first=EscenarioComparacion.generar(profile,987654321L);
        var replay=EscenarioComparacion.generar(profile,987654321L);
        assertEquals(first.pedidos(),replay.pedidos());
        assertEquals(first.stockInicial(),replay.stockInicial());
        assertEquals(first.fingerprint(),replay.fingerprint());
        assertEquals(20,first.pedidos().size());
        assertEquals(EnumSet.allOf(NivelServicio.class),EnumSet.copyOf(first.pedidos().stream().map(EscenarioComparacion.PedidoProgramado::servicio).toList()));
        assertEquals(500,first.capacidadAlmacen());
        assertTrue(first.volumenStockInicial()<=first.capacidadAlmacen());
        assertEquals(20,first.pedidos().stream().map(EscenarioComparacion.PedidoProgramado::productoId).distinct().count()*4);
        assertEquals(2,first.trabajadores()); assertEquals(20,first.milisegundosPorUnidad());
        assertEquals(5,first.intervaloLlegadasMillis()); assertEquals(80,first.intervaloEnvejecimientoMillis());
        assertTrue(first.pedidos().stream().allMatch(p->p.llegadaRelativaMillis()%5==0));
        Map<String,Producto> products=new HashMap<>(); CatalogoProductos.todos().forEach(p->products.put(p.id(),p));
        assertDoesNotThrow(()->new Almacen(first.capacidadesUbicaciones()).validarCabeEnAlmacenVacio(products,first.stockInicial()));
        assertEquals(first.pedidos().stream().mapToInt(EscenarioComparacion.PedidoProgramado::cantidad).sum(),
                first.stockInicial().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void comparisonRunsAllPoliciesInFreshClosedSystemsWithSameFingerprint() {
        var scenario=EscenarioComparacion.generar(EscenarioComparacion.PERFILES.get(0),77);
        var result=new ComparadorPoliticas().ejecutar(scenario,new ComparadorPoliticas.Cancelacion(),p->{});
        assertEquals(ComparadorPoliticas.Estado.COMPLETA,result.estado(),result.mensaje());
        assertEquals(List.of(EstrategiaPlanificacion.values()),result.politicas().stream().map(ComparadorPoliticas.ResultadoPolitica::politica).toList());
        assertEquals(3,result.politicas().stream().map(ComparadorPoliticas.ResultadoPolitica::ejecucionId).distinct().count());
        assertTrue(result.politicas().stream().allMatch(r->r.fingerprintEntradas().equals(scenario.fingerprint())));
        assertTrue(result.politicas().stream().allMatch(r->r.estado()==ComparadorPoliticas.Estado.COMPLETA));
        assertTrue(result.politicas().stream().allMatch(r->r.stockInicialVerificado()&&r.trabajadoresTerminados()
                &&r.consumidorTerminado()&&r.recursosLiberados()));
        for(var run:result.politicas()) {
            var m=run.metricas(); assertEquals(15,m.previstos()); assertEquals(15,m.aceptados()); assertEquals(15,m.completados());
            assertEquals(0,m.cancelados()); assertEquals(0,m.fallidos());
            assertNotNull(m.esperaMediaMillis()); assertNotNull(m.esperaMaximaMillis());
            assertNotNull(m.procesamientoMedioMillis()); assertNotNull(m.llegadaAFinMediaMillis());
            assertTrue(m.duracionTotalMillis()>0); assertTrue(m.pedidosPorSegundo()>0);
            assertEquals(5,run.esperaMediaPorServicioMillis().size());
            assertEquals(TipoRecurso.values().length,run.utilizacionPorRecurso().size());
            assertTrue(run.utilizacionPorRecurso().values().stream().allMatch(v->v>=0&&v<=1));
        }
    }

    @Test void metricFormulasUseRealNanosecondsAndAbsentSamplesAreNotZero() {
        var samples=List.of(
                new ComparadorPoliticas.PedidoEjecutadoVista(1_000_000L,4_000_000L,14_000_000L,EstadoPedido.FINALIZADO,null,NivelServicio.EXPRES),
                new ComparadorPoliticas.PedidoEjecutadoVista(2_000_000L,null,null,EstadoPedido.ESPERANDO_STOCK,null,NivelServicio.ECONOMICO));
        var metrics=ComparadorPoliticas.calcularMetricas(2,2,samples,20_000_000L,0,0);
        assertEquals(3d,metrics.esperaMediaMillis()); assertEquals(3d,metrics.esperaMaximaMillis());
        assertEquals(10d,metrics.procesamientoMedioMillis()); assertEquals(13d,metrics.llegadaAFinMediaMillis());
        assertEquals(20d,metrics.duracionTotalMillis()); assertEquals(50d,metrics.pedidosPorSegundo());
        var noSamples=ComparadorPoliticas.calcularMetricas(1,0,List.of(),0,0,0);
        assertNull(noSamples.esperaMediaMillis()); assertNull(noSamples.esperaMaximaMillis());
        assertNull(noSamples.procesamientoMedioMillis()); assertNull(noSamples.pedidosPorSegundo());
    }

    @Test void timeoutIsExplicitAndCleansUpWithoutPresentingPartialTimesAsComplete() {
        var scenario=EscenarioComparacion.generar(EscenarioComparacion.PERFILES.get(0),9).conLimiteEjecucion(1);
        var result=new ComparadorPoliticas().ejecutar(scenario,new ComparadorPoliticas.Cancelacion(),p->{});
        assertEquals(ComparadorPoliticas.Estado.LIMITE_TIEMPO,result.estado());
        var first=result.politicas().get(0);
        assertEquals(ComparadorPoliticas.Estado.LIMITE_TIEMPO,first.estado());
        assertNotNull(first.metricas()); assertNull(first.metricas().esperaMediaMillis());
        assertTrue(first.trabajadoresTerminados()&&first.consumidorTerminado()&&first.recursosLiberados());
        assertTrue(result.politicas().subList(1,3).stream().allMatch(r->r.estado()==ComparadorPoliticas.Estado.NO_EJECUTADA));
    }

    @Test void cancellationDuringRunStopsTheCurrentPolicyAndReleasesThreads() throws Exception {
        var scenario=EscenarioComparacion.generar(EscenarioComparacion.PERFILES.get(1),31);
        var token=new ComparadorPoliticas.Cancelacion(); var started=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<ComparadorPoliticas.Resultado> future=executor.submit(()->new ComparadorPoliticas().ejecutar(scenario,token,p->{
                if(p.mensaje().startsWith("Preparando")) started.countDown();
            }));
            assertTrue(started.await(5,TimeUnit.SECONDS)); token.cancelar();
            var result=future.get(10,TimeUnit.SECONDS);
            assertEquals(ComparadorPoliticas.Estado.CANCELADA,result.estado());
            assertEquals(ComparadorPoliticas.Estado.CANCELADA,result.politicas().get(0).estado());
            assertTrue(result.politicas().get(0).trabajadoresTerminados());
            assertTrue(result.politicas().get(0).consumidorTerminado());
            assertTrue(result.politicas().get(0).recursosLiberados());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @Test void comparisonDoesNotMutateInteractiveMotorState() throws Exception {
        GestorRecursos resources=new GestorRecursos();
        var queue=new ArrayBlockingQueue<SolicitudRecepcion>(4); var warehouse=new Almacen(List.of(100,100,100,100,100));
        var reception=new Recepcion(queue,warehouse.capacidadTotal()); var inventory=new Inventario(queue,reception,warehouse,resources);
        var interactive=new MotorSimulacion(1,new TiempoSimulado(20),EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,80,resources);
        try {
            interactive.conectarInventario(inventory); interactive.iniciar();
            Cliente client=new Cliente("UI","ui@example.local",NivelServicio.ESTANDAR);
            Pedido pending=new Pedido(client,NivelServicio.ESTANDAR,List.of(new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"),1)));
            interactive.enviar(pending);
            assertTrue(interactive.esperarEstado(pending.id(),EstadoPedido.ESPERANDO_STOCK,3,TimeUnit.SECONDS));
            var before=interactive.snapshot();
            var result=new ComparadorPoliticas().ejecutar(EscenarioComparacion.generar(EscenarioComparacion.PERFILES.get(0),4),
                    new ComparadorPoliticas.Cancelacion(),p->{});
            assertEquals(ComparadorPoliticas.Estado.COMPLETA,result.estado());
            var after=interactive.snapshot();
            assertTrue(after.activo()); assertEquals(before.pedidos().size(),after.pedidos().size());
            assertEquals(pending.id(),after.pedidos().get(0).pedido().id());
            assertEquals(EstadoPedido.ESPERANDO_STOCK,after.pedidos().get(0).estado());
        } finally { interactive.close(); }
    }
}
