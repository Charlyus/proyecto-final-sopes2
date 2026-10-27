package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneradorYClientesTest {
    private record Parts(GestorRecursos resources, Inventario inventory, Recepcion reception) { }
    private Parts parts() {
        var resources = new GestorRecursos(); var queue = new ArrayBlockingQueue<SolicitudRecepcion>(16);
        var reception = new Recepcion(queue, 2000);
        return new Parts(resources, new Inventario(queue, reception, new Almacen(List.of(500,500,500,500,500)), resources), reception);
    }
    private MotorSimulacion motor(Parts p) {
        var m = new MotorSimulacion(2, new gt.redxela.concurrencia.TiempoSimulado(5_000),
                EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 100, p.resources());
        m.conectarInventario(p.inventory()); m.iniciar(); return m;
    }

    @Test void clientesValidanCorreoUnicoNormalizadoYPedidoConservaServicio() {
        var registry = new RegistroClientes();
        Cliente original = registry.registrar(" Ana ", " Ana@Example.test ", NivelServicio.EXPRES);
        assertEquals("ana@example.test", original.correo());
        assertThrows(IllegalArgumentException.class, () -> registry.registrar("Ana 2", "ana@example.test", NivelServicio.ESTANDAR));
        var order = new Pedido(original, original.nivelServicio(), List.of(new LineaPedido(TipoMercancia.DOCUMENTOS, 1)));
        registry.actualizarServicio(original.id(), NivelServicio.ECONOMICO);
        assertEquals(NivelServicio.EXPRES, order.cliente().nivelServicio());
        assertEquals(NivelServicio.EXPRES, order.nivelServicio());
        assertEquals(NivelServicio.ECONOMICO, registry.buscar(original.id()).orElseThrow().nivelServicio());
        assertThrows(IllegalArgumentException.class, () -> new Cliente("No válido", "correo-inválido"));
    }

    @Test void mismaSemillaRepiteDatosYSecuenciaIncluyeLosCincoNiveles() {
        var registry = new RegistroClientes();
        var config = new GeneradorPedidos.Configuracion(100, 2, 8, 991L);
        var one = new GeneradorPedidos.DataSequence(config, registry);
        var two = new GeneradorPedidos.DataSequence(config, registry);
        List<String> a = new ArrayList<>(), b = new ArrayList<>(); Set<NivelServicio> levels = EnumSet.noneOf(NivelServicio.class); boolean hasMultipleLines=false;
        for (int i=0;i<20;i++) {
            Pedido x=one.siguiente(), y=two.siguiente(); levels.add(x.nivelServicio());
            hasMultipleLines |= x.lineas().size()>1;
            a.add(x.nivelServicio()+"/"+x.cliente().correo()+"/"+x.lineas());
            b.add(y.nivelServicio()+"/"+y.cliente().correo()+"/"+y.lineas());
            assertTrue(x.totalUnidades()>=2 && x.totalUnidades()<=8);
        }
        assertEquals(a,b); assertEquals(EnumSet.allOf(NivelServicio.class),levels); assertTrue(hasMultipleLines);
        assertTrue(registry.snapshot().stream().allMatch(c -> c.nombre().startsWith("[Sintético]")));
    }

    @Test void noDuplicaGeneradoresDetieneTicksYConservaPedidosAceptadosEsperandoStock() throws Exception {
        Parts parts=parts(); MotorSimulacion motor=motor(parts); CountDownLatch arrivals=new CountDownLatch(1);
        var generator=new GeneradorPedidos(motor,new RegistroClientes(),e -> fail(e),p -> arrivals.countDown());
        try {
            var config=new GeneradorPedidos.Configuracion(1000,1,3,7L);
            assertTrue(generator.iniciar(config)); assertFalse(generator.iniciar(config));
            assertTrue(arrivals.await(5,TimeUnit.SECONDS),"llegadas del generador");
            generator.detener(); long count=generator.aceptados(); int orders=motor.snapshot().pedidos().size();
            assertEquals(1,count); assertEquals(count,orders);
            assertTrue(motor.snapshot().pedidos().stream().allMatch(p -> p.origen()==OrigenPedido.AUTOMATICO && p.estado()==EstadoPedido.ESPERANDO_STOCK));
            assertTrue(generator.terminado());
            assertTrue(generator.iniciar(new GeneradorPedidos.Configuracion(1000,1,1,8L)));
            generator.close(); assertTrue(generator.aceptados()>=count);
        } finally { generator.close(); motor.close(); }
        assertTrue(generator.terminado()); assertTrue(motor.cierreCompleto());
        assertTrue(parts.resources().snapshot().values().stream().allMatch(r -> r.disponibles()==r.capacidad()));
    }

    @Test void enviosConcurrentesTienenIdsUnicosYRegistroNoPierdeCuentas() throws Exception {
        var registry=new RegistroClientes(); ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        List<Future<Cliente>> futures=new ArrayList<>();
        try {
            for(int i=0;i<40;i++) { final int n=i; futures.add(pool.submit(()->{start.await(); return registry.registrar("C"+n,"c"+n+"@test.local",NivelServicio.ESTANDAR);})); }
            start.countDown(); for(Future<Cliente> f:futures) f.get(2,TimeUnit.SECONDS);
            assertEquals(40,registry.snapshot().size());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)); }
    }

    @Test void ingresoManualConcurrenteAlMotorNoDuplicaNiPierdeIds() throws Exception {
        Parts p=parts(); MotorSimulacion motor=motor(p); var pool=Executors.newFixedThreadPool(8);
        CountDownLatch ready=new CountDownLatch(8), start=new CountDownLatch(1); List<Future<?>> futures=new ArrayList<>();
        try {
            for(int i=0;i<40;i++) futures.add(pool.submit(()->{
                ready.countDown(); start.await();
                motor.enviar(new Pedido(new Cliente("Concurrente","id@redxela.test"),NivelServicio.ESTANDAR,
                        List.of(new LineaPedido(TipoMercancia.DOCUMENTOS,1)))); return null;
            }));
            assertTrue(ready.await(2,TimeUnit.SECONDS)); start.countDown();
            for(Future<?> f:futures) f.get(3,TimeUnit.SECONDS);
            var snapshot=motor.snapshot(); assertEquals(40,snapshot.pedidos().size());
            assertEquals(40,snapshot.pedidos().stream().map(x->x.pedido().id()).distinct().count());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)); motor.close(); }
    }
}
