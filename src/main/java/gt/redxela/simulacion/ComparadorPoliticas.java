package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.concurrencia.TipoRecurso;
import gt.redxela.concurrencia.TiempoSimulado;
import gt.redxela.concurrencia.EscalaTiempoSimulado;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Ejecuta secuencialmente las tres políticas sobre instancias nuevas con entradas comunes. */
public final class ComparadorPoliticas {
    public enum Estado { COMPLETA, CANCELADA, LIMITE_TIEMPO, FALLIDA, NO_EJECUTADA }
    public record Progreso(EstrategiaPlanificacion politica, int indicePolitica,
                           int pedidosCompletados, int pedidosPrevistos, String mensaje) { }
    public record Metricas(int previstos, int aceptados, int completados, int cancelados, int fallidos,
                           Double esperaMediaMillis, Double esperaMaximaMillis,
                           Double procesamientoMedioMillis, Double llegadaAFinMediaMillis,
                           Double duracionTotalMillis, Double pedidosPorSegundo) {
        public boolean tieneTiemposDeEspera() { return esperaMediaMillis != null; }
    }
    public record ResultadoPolitica(EstrategiaPlanificacion politica, Estado estado, UUID ejecucionId,
                                    String fingerprintEntradas, Metricas metricas,
                                    Map<NivelServicio,Double> esperaMediaPorServicioMillis,
                                    Map<TipoRecurso,Double> utilizacionPorRecurso,
                                    boolean stockInicialVerificado, boolean trabajadoresTerminados,
                                    boolean consumidorTerminado, boolean recursosLiberados, String error) {
        public ResultadoPolitica {
            esperaMediaPorServicioMillis=Map.copyOf(esperaMediaPorServicioMillis);
            utilizacionPorRecurso=Map.copyOf(utilizacionPorRecurso);
        }
    }
    public record Resultado(EscenarioComparacion escenario, Estado estado,
                            List<ResultadoPolitica> politicas, String mensaje) {
        public Resultado { politicas=List.copyOf(politicas); }
    }

    /** El token interrumpe la espera del comparador y permite limpieza inmediata. */
    public static final class Cancelacion {
        private final AtomicBoolean requested=new AtomicBoolean();
        private volatile Thread runner;
        public void cancelar() { requested.set(true); Thread active=runner; if(active!=null) active.interrupt(); }
        public boolean solicitada() { return requested.get(); }
    }

    private record PedidoEjecutado(String idLogico, NivelServicio servicio, Long llegada,
                                   Long inicio, Long fin, EstadoPedido estado, String error) { }
    private record Calculo(Metricas metricas, Map<NivelServicio,Double> porServicio,
                           Map<TipoRecurso,Double> utilizacion) { }
    private final AtomicBoolean ejecutando=new AtomicBoolean();

    public Resultado ejecutar(EscenarioComparacion scenario,Cancelacion cancellation,
                              Consumer<Progreso> progress) {
        Objects.requireNonNull(scenario); Objects.requireNonNull(cancellation); Objects.requireNonNull(progress);
        long timeoutMillis=scenario.limiteEjecucionMillis();
        if(!ejecutando.compareAndSet(false,true)) throw new IllegalStateException("ya hay una comparación activa");
        cancellation.runner=Thread.currentThread();
        List<ResultadoPolitica> results=new ArrayList<>();
        Estado overall=Estado.COMPLETA; String message="Tres ejecuciones aisladas completadas";
        try {
            EstrategiaPlanificacion[] strategies=EstrategiaPlanificacion.values();
            for(int i=0;i<strategies.length;i++) {
                if(cancellation.solicitada()) { overall=Estado.CANCELADA; message="Comparación cancelada por el usuario"; break; }
                EstrategiaPlanificacion strategy=strategies[i];
                progress.accept(new Progreso(strategy,i,0,scenario.pedidos().size(),"Preparando estado aislado y stock inicial"));
                ResultadoPolitica result=runOne(scenario,strategy,cancellation,i,progress);
                results.add(result);
                if(result.estado()!=Estado.COMPLETA) {
                    overall=result.estado(); message=switch(overall) {
                        case CANCELADA -> "Comparación cancelada; las filas previas no forman una comparación completa";
                        case LIMITE_TIEMPO -> "Una ejecución agotó el límite; no se presentan sus métricas parciales como completas";
                        default -> "Una ejecución falló: "+Objects.toString(result.error(),"error sin detalle");
                    };
                    break;
                }
            }
            if(overall!=Estado.COMPLETA) {
                for(int i=results.size();i<EstrategiaPlanificacion.values().length;i++) {
                    results.add(notRun(EstrategiaPlanificacion.values()[i],scenario,overall==Estado.CANCELADA?"Cancelada antes de iniciar":"No ejecutada porque una política anterior no terminó"));
                }
            }
            return new Resultado(scenario,overall,results,message);
        } finally { cancellation.runner=null; ejecutando.set(false); }
    }

    private ResultadoPolitica runOne(EscenarioComparacion scenario,EstrategiaPlanificacion strategy,
                                     Cancelacion cancellation,int policyIndex,
                                     Consumer<Progreso> progress) {
        long timeoutMillis=scenario.limiteEjecucionMillis();
        UUID runId=UUID.randomUUID(); GestorRecursos resources=new GestorRecursos();
        ArrayBlockingQueue<SolicitudRecepcion> queue=new ArrayBlockingQueue<>(8);
        Almacen warehouse=new Almacen(scenario.capacidadesUbicaciones());
        Recepcion reception=new Recepcion(queue,warehouse.capacidadTotal());
        Inventario inventory=new Inventario(queue,reception,warehouse,resources);
        MotorSimulacion motor=new MotorSimulacion(scenario.trabajadores(),
                new TiempoSimulado(scenario.milisegundosPorUnidad()),strategy,
                scenario.intervaloEnvejecimientoMillis(),resources,
                new EscalaTiempoSimulado(scenario.escalaTiempo()));
        motor.conectarInventario(inventory);
        ScheduledExecutorService arrivals=Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"redxela-comparacion-llegadas-"+strategy.name().toLowerCase(Locale.ROOT)); t.setDaemon(false); return t;
        });
        Set<UUID> accepted=ConcurrentHashMap.newKeySet();
        ConcurrentMap<String,String> sendFailures=new ConcurrentHashMap<>();
        CountDownLatch allArrivals=new CountDownLatch(scenario.pedidos().size());
        long startedAt=0; long deadline=0;
        EnumMap<TipoRecurso,Long> baselineByResource=new EnumMap<>(TipoRecurso.class);
        boolean stockVerified=false; Estado status=Estado.FALLIDA; String error=null;
        Metricas metrics=null; Map<NivelServicio,Double> levelWait=Map.of(); Map<TipoRecurso,Double> utilization=Map.of();
        try {
            motor.iniciar();
            Map<String,UUID> receiptIds=loadInitialStock(scenario,reception);
            for(var receipt:receiptIds.entrySet()) {
                EstadoRecepcionVista applied=reception.esperarEstado(receipt.getValue(),EstadoRecepcion.APLICADA,5,TimeUnit.SECONDS);
                if(applied==null||applied.estado()!=EstadoRecepcion.APLICADA)
                    throw new IllegalStateException("No se aplicó el stock inicial de "+receipt.getKey()+": "+(applied==null?"sin estado":applied.motivo()));
            }
            verifyInitialInventory(scenario,inventory.snapshot()); stockVerified=true;
            resources.snapshot().forEach((type,state)->baselineByResource.put(type,state.nanosOcupadosAcumulados()));
            startedAt=System.nanoTime(); deadline=saturatingAdd(startedAt,TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
            // t=0 comienza justo después de preparar y verificar el estado inicial.
            for(EscenarioComparacion.PedidoProgramado spec:scenario.pedidos()) {
                arrivals.schedule(()-> {
                    try {
                        if(cancellation.solicitada()) return;
                        Pedido order=materialize(spec);
                        motor.enviar(order,OrigenPedido.DEMOSTRACION);
                        accepted.add(order.id());
                        long completedCount=motor.snapshot().pedidos().stream().filter(o->o.estado()==EstadoPedido.FINALIZADO).count();
                        progress.accept(new Progreso(strategy,policyIndex,(int)completedCount,scenario.pedidos().size(),
                                "Reproduciendo llegadas · "+spec.idLogico()));
                    } catch(RuntimeException failure) { sendFailures.put(spec.idLogico(),message(failure)); }
                    finally { allArrivals.countDown(); }
                },spec.llegadaRelativaMillis(),TimeUnit.MILLISECONDS);
            }
            long remaining=deadline-System.nanoTime();
            boolean arrivalsDone=remaining>0&&allArrivals.await(remaining,TimeUnit.NANOSECONDS);
            arrivals.shutdown();
            if(!arrivalsDone) {
                status=cancellation.solicitada()?Estado.CANCELADA:Estado.LIMITE_TIEMPO;
                error=cancellation.solicitada()?"Cancelada durante la reproducción de llegadas":"Tiempo límite durante la reproducción de llegadas";
            } else if(cancellation.solicitada()) {
                status=Estado.CANCELADA; error="Cancelada por el usuario";
            } else if(!sendFailures.isEmpty()) {
                status=Estado.FALLIDA; error="Pedidos rechazados al reproducir: "+sendFailures;
            } else {
                Set<UUID> expected=Set.copyOf(accepted);
                remaining=deadline-System.nanoTime();
                boolean terminal=false;
                while(remaining>0&&!cancellation.solicitada()) {
                    terminal=motor.esperarPedidosTerminales(expected,Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
                    if(terminal) break;
                    long completedNow=motor.snapshot().pedidos().stream().filter(o->o.estado()==EstadoPedido.FINALIZADO).count();
                    progress.accept(new Progreso(strategy,policyIndex,(int)completedNow,scenario.pedidos().size(),"Procesando pedidos"));
                    remaining=deadline-System.nanoTime();
                }
                if(!terminal) {
                    status=cancellation.solicitada()?Estado.CANCELADA:Estado.LIMITE_TIEMPO;
                    error=cancellation.solicitada()?"Cancelada mientras se procesaban pedidos":"Tiempo límite esperando pedidos terminales";
                } else if(cancellation.solicitada()) {
                    status=Estado.CANCELADA; error="Cancelada por el usuario";
                } else {
                    remaining=deadline-System.nanoTime();
                    if(remaining<=0||!resources.esperarTodoLibre(remaining,TimeUnit.NANOSECONDS)) {
                        status=Estado.LIMITE_TIEMPO; error="Tiempo límite esperando liberación de recursos";
                    }
                    if(status==Estado.LIMITE_TIEMPO) throw new ExecutionTimeout(error);
                    SnapshotMotor finalSnapshot=motor.snapshot();
                    List<PedidoEjecutado> orders=mapOrders(scenario,finalSnapshot);
                    long lastFinish=orders.stream().filter(o->o.fin()!=null).mapToLong(PedidoEjecutado::fin).max().orElse(startedAt);
                    long completionDurationNanos=Math.max(1,lastFinish-startedAt);
                    GestorRecursos.SnapshotCompleto finalResources=resources.snapshotCompleto();
                    long resourceWindowNanos=Math.max(1,System.nanoTime()-startedAt);
                    Map<TipoRecurso,Long> occupiedDelta=new EnumMap<>(TipoRecurso.class);
                    finalResources.recursos().forEach((type,state)->occupiedDelta.put(type,
                            Math.max(0,state.nanosOcupadosAcumulados()-baselineByResource.getOrDefault(type,0L))));
                    Calculo calculation=calculate(orders,scenario,accepted.size(),sendFailures.size(),startedAt,lastFinish,
                            completionDurationNanos,resourceWindowNanos,occupiedDelta,finalResources.recursos());
                    metrics=calculation.metricas(); levelWait=calculation.porServicio(); utilization=calculation.utilizacion();
                    boolean allCompleted=orders.size()==scenario.pedidos().size()&&orders.stream().allMatch(o->o.estado()==EstadoPedido.FINALIZADO&&o.error()==null);
                    status=allCompleted?Estado.COMPLETA:Estado.FALLIDA;
                    if(!allCompleted) { error="La ejecución llegó a estado terminal, pero no completó todos los pedidos"; metrics=null; levelWait=Map.of(); utilization=Map.of(); }
                }
            }
        } catch(ExecutionTimeout timeout) {
            status=Estado.LIMITE_TIEMPO; error=timeout.getMessage();
        } catch(InterruptedException interrupted) {
            status=cancellation.solicitada()?Estado.CANCELADA:Estado.FALLIDA;
            error=cancellation.solicitada()?"Cancelada por el usuario":"Ejecución interrumpida";
            Thread.interrupted();
        } catch(RuntimeException failure) {
            status=Estado.FALLIDA; error=message(failure);
        } finally {
            arrivals.shutdownNow();
            try { arrivals.awaitTermination(5,TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.interrupted(); if(error==null) error="Interrumpida al detener llegadas"; }
            reception.close(); motor.close();
        }
        Map<TipoRecurso,GestorRecursos.EstadoRecurso> remainingResources=resources.snapshot();
        boolean released=remainingResources.values().stream().allMatch(r->r.disponibles()==r.capacidad());
        boolean workersTerminated=motor.cierreCompleto();
        boolean consumerTerminated=inventory.consumidorTerminado();
        if((!released||!workersTerminated||!consumerTerminated)&&status==Estado.COMPLETA) {
            status=Estado.FALLIDA; error="La ejecución terminó pero no liberó todos sus hilos o recursos"; metrics=null; levelWait=Map.of(); utilization=Map.of();
        }
        SnapshotMotor closedSnapshot=motor.snapshot();
        int completed=(int)closedSnapshot.pedidos().stream().filter(o->o.estado()==EstadoPedido.FINALIZADO).count();
        if(metrics==null) {
            int cancelled=(int)closedSnapshot.pedidos().stream().filter(o->o.estado()==EstadoPedido.CANCELADO&&o.error()==null).count();
            int internalFailures=(int)closedSnapshot.pedidos().stream().filter(o->o.error()!=null).count();
            metrics=new Metricas(scenario.pedidos().size(),accepted.size(),completed,cancelled,
                    sendFailures.size()+internalFailures,null,null,null,null,null,null);
        }
        progress.accept(new Progreso(strategy,policyIndex,completed,scenario.pedidos().size(),status.toString()));
        return new ResultadoPolitica(strategy,status,runId,scenario.fingerprint(),metrics,levelWait,utilization,
                stockVerified,workersTerminated,consumerTerminated,released,error);
    }

    private static Map<String,UUID> loadInitialStock(EscenarioComparacion scenario,Recepcion reception) {
        Map<String,UUID> ids=new LinkedHashMap<>();
        scenario.stockInicial().forEach((id,quantity)->{
            EstadoRecepcionVista result=reception.recibir(CatalogoProductos.porId(id),quantity);
            if(result.estado()!=EstadoRecepcion.EN_COLA) throw new IllegalStateException("Stock inicial rechazado para "+id+": "+result.motivo());
            ids.put(id,result.id());
        });
        return ids;
    }
    private static void verifyInitialInventory(EscenarioComparacion scenario,SnapshotInventario actual) {
        Map<String,Integer> physical=new HashMap<>(); actual.stock().forEach(s->physical.put(s.productoId(),s.fisico()));
        if(!physical.equals(scenario.stockInicial())) throw new IllegalStateException("Stock físico inicial distinto al declarado: "+physical);
        int lotTotal=actual.lotes().stream().mapToInt(Almacen.ParteLote::cantidad).sum();
        int physicalTotal=actual.stock().stream().mapToInt(EstadoStock::fisico).sum();
        if(lotTotal!=physicalTotal) throw new IllegalStateException("Lotes y existencias iniciales no coinciden");
        long used=actual.ubicaciones().stream().mapToLong(Almacen.Ubicacion::volumenUtilizado).sum();
        if(used>scenario.capacidadAlmacen()||scenario.volumenStockInicial()>scenario.capacidadAlmacen())
            throw new IllegalStateException("El stock inicial excede la capacidad de almacenamiento");
    }
    private static Pedido materialize(EscenarioComparacion.PedidoProgramado spec) {
        UUID clientId=UUID.nameUUIDFromBytes("redxela-comparacion-cliente".getBytes(StandardCharsets.UTF_8));
        Cliente client=new Cliente(clientId,"Cliente escenario","escenario@redxela.local",NivelServicio.ESTANDAR);
        return new Pedido(spec.pedidoUuid(),client,spec.servicio(),List.of(
                new LineaPedido(CatalogoProductos.porId(spec.productoId()),spec.cantidad())),EstadoPedido.RECIBIDO,Instant.now());
    }
    private static List<PedidoEjecutado> mapOrders(EscenarioComparacion scenario,SnapshotMotor snapshot) {
        Map<UUID,EstadoPedidoMotor> byId=new HashMap<>(); snapshot.pedidos().forEach(o->byId.put(o.pedido().id(),o));
        List<PedidoEjecutado> mapped=new ArrayList<>();
        for(var spec:scenario.pedidos()) {
            EstadoPedidoMotor order=byId.get(spec.pedidoUuid());
            if(order!=null) mapped.add(new PedidoEjecutado(spec.idLogico(),spec.servicio(),order.llegadaNanos(),order.inicioNanos(),
                    order.finNanos(),order.estado(),order.error()));
        }
        return List.copyOf(mapped);
    }

    /** Fórmulas deterministas compartidas: todas las duraciones recibidas y devueltas están en milisegundos reales. */
    public static Metricas calcularMetricas(int planned,int accepted,List<PedidoEjecutadoVista> orders,
                                            long durationNanos,int failed,int cancelled) {
        Objects.requireNonNull(orders);
        List<Long> waits=orders.stream().filter(o->o.arrivalNanos()!=null&&o.startNanos()!=null)
                .map(o->Math.max(0,o.startNanos()-o.arrivalNanos())).toList();
        List<Long> processing=orders.stream().filter(o->o.startNanos()!=null&&o.finishNanos()!=null)
                .map(o->Math.max(0,o.finishNanos()-o.startNanos())).toList();
        List<Long> sojourn=orders.stream().filter(o->o.arrivalNanos()!=null&&o.finishNanos()!=null&&o.state()==EstadoPedido.FINALIZADO)
                .map(o->Math.max(0,o.finishNanos()-o.arrivalNanos())).toList();
        Double meanWait=averageMillis(waits), maxWait=waits.isEmpty()?null:waits.stream().mapToLong(Long::longValue).max().orElseThrow()/1_000_000d;
        Double meanProcessing=averageMillis(processing), meanSojourn=averageMillis(sojourn);
        Double duration=durationNanos>0?durationNanos/1_000_000d:null;
        Double throughput=durationNanos>0?(double)sojourn.size()/(durationNanos/1_000_000_000d):null;
        int completed=(int)orders.stream().filter(o->o.state()==EstadoPedido.FINALIZADO&&o.error()==null).count();
        int actualCancelled=(int)orders.stream().filter(o->o.state()==EstadoPedido.CANCELADO&&o.error()==null).count()+cancelled;
        int actualFailed=failed+(int)orders.stream().filter(o->o.error()!=null).count();
        return new Metricas(planned,accepted,completed,actualCancelled,actualFailed,meanWait,maxWait,meanProcessing,meanSojourn,duration,throughput);
    }
    public record PedidoEjecutadoVista(Long arrivalNanos,Long startNanos,Long finishNanos,
                                       EstadoPedido state,String error,NivelServicio servicio) { }
    private static Double averageMillis(List<Long> nanos) { return nanos.isEmpty()?null:nanos.stream().mapToLong(Long::longValue).average().orElseThrow()/1_000_000d; }

    private static Calculo calculate(List<PedidoEjecutado> orders,EscenarioComparacion scenario,int accepted,
                                     int failedArrivals,long startedAt,long lastFinish,long completionDuration,
                                     long resourceWindow,
                                     Map<TipoRecurso,Long> occupied,Map<TipoRecurso,GestorRecursos.EstadoRecurso> finalResources) {
        List<PedidoEjecutadoVista> view=orders.stream().map(o->new PedidoEjecutadoVista(o.llegada(),o.inicio(),o.fin(),o.estado(),o.error(),o.servicio())).toList();
        Metricas raw=calcularMetricas(scenario.pedidos().size(),accepted,view,completionDuration,failedArrivals,0);
        Metricas metrics=new Metricas(raw.previstos(),raw.aceptados(),raw.completados(),raw.cancelados(),raw.fallidos(),
                raw.esperaMediaMillis(),raw.esperaMaximaMillis(),raw.procesamientoMedioMillis(),raw.llegadaAFinMediaMillis(),
                Math.max(0,lastFinish-startedAt)/1_000_000d,raw.pedidosPorSegundo());
        EnumMap<NivelServicio,Double> byLevel=new EnumMap<>(NivelServicio.class);
        for(NivelServicio level:NivelServicio.values()) {
            List<Long> waits=orders.stream().filter(o->o.servicio()==level&&o.inicio()!=null&&o.llegada()!=null)
                    .map(o->Math.max(0,o.inicio()-o.llegada())).toList();
            Double avg=averageMillis(waits); if(avg!=null) byLevel.put(level,avg);
        }
        EnumMap<TipoRecurso,Double> utilization=new EnumMap<>(TipoRecurso.class);
        for(TipoRecurso type:TipoRecurso.values()) {
            GestorRecursos.EstadoRecurso resource=finalResources.get(type);
            if(resource==null||resourceWindow<=0) continue;
            double value=(double)occupied.getOrDefault(type,0L)/(resourceWindow*resource.capacidad());
            utilization.put(type,Math.min(1d,Math.max(0d,value)));
        }
        return new Calculo(metrics,Map.copyOf(byLevel),Map.copyOf(utilization));
    }
    private static ResultadoPolitica notRun(EstrategiaPlanificacion strategy,EscenarioComparacion scenario,String reason) {
        return new ResultadoPolitica(strategy,Estado.NO_EJECUTADA,UUID.randomUUID(),scenario.fingerprint(),null,
                Map.of(),Map.of(),false,true,true,true,reason);
    }
    private static long saturatingAdd(long a,long b) { try{return Math.addExact(a,b);}catch(ArithmeticException ex){return Long.MAX_VALUE;} }
    private static String message(Throwable ex) { return ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(); }
    private static final class ExecutionTimeout extends RuntimeException { ExecutionTimeout(String message){super(message);} }
}
