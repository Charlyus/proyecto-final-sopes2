package gt.redxela.simulacion;

import gt.redxela.concurrencia.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.*;
import gt.redxela.inventario.Inventario;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Motor en memoria. El lock protege solamente despacho/estados; nunca cubre la duración del trabajo. */
public final class MotorSimulacion implements AutoCloseable {
    private static final class RuntimeOrder {
        final Pedido pedido; final long arrival; final long order; final OrigenPedido origen;
        EstadoPedido state = EstadoPedido.ESPERANDO_STOCK; Long started; Long finished;
        Thread worker; boolean consumed; String error;
        RuntimeOrder(Pedido p, long arrival, long order, OrigenPedido origen) { pedido=p; this.arrival=arrival; this.order=order; this.origen=origen; }
    }
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<UUID, RuntimeOrder> orders = new LinkedHashMap<>();
    private final List<RuntimeOrder> pending = new ArrayList<>();
    private final ExecutorService workers;
    private final GestorRecursos resourceManager;
    private final PlanificadorPedidos planner;
    private final TiempoSimulado simulatedTime;
    private final long observedFrom = System.nanoTime();
    private EstrategiaPlanificacion strategy;
    private long sequence;
    private boolean running;
    private boolean closing;
    private Inventario inventario;

    public MotorSimulacion(int numeroTrabajadores, TiempoSimulado tiempo,
                           EstrategiaPlanificacion estrategia, long intervaloEnvejecimientoMillis) {
        this(numeroTrabajadores,tiempo,estrategia,intervaloEnvejecimientoMillis,new GestorRecursos());
    }

    public MotorSimulacion(int numeroTrabajadores, TiempoSimulado tiempo,
                           EstrategiaPlanificacion estrategia, long intervaloEnvejecimientoMillis,
                           GestorRecursos resourceManager) {
        if (numeroTrabajadores <= 0) throw new IllegalArgumentException("se requiere al menos un trabajador");
        this.simulatedTime = Objects.requireNonNull(tiempo);
        this.strategy = Objects.requireNonNull(estrategia);
        this.planner = new PlanificadorPedidos(TimeUnit.MILLISECONDS.toNanos(intervaloEnvejecimientoMillis));
        this.resourceManager=Objects.requireNonNull(resourceManager);
        this.workers = Executors.newFixedThreadPool(numeroTrabajadores, r -> {
            Thread t = new Thread(r, "redxela-trabajador"); t.setDaemon(false); return t;
        });
    }

    public void iniciar() {
        lock.lock();
        try {
            if (closing) throw new IllegalStateException("motor cerrado");
            if (running) return;
            if (inventario == null) throw new IllegalStateException("conecte Inventario antes de iniciar el motor");
            inventario.iniciar();
            running = true;
            int count = ((ThreadPoolExecutor) workers).getCorePoolSize();
            for (int i=0; i<count; i++) workers.submit(this::workerLoop);
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    public void enviar(Pedido pedido) {
        enviar(pedido, OrigenPedido.MANUAL);
    }

    public void enviar(Pedido pedido, OrigenPedido origen) {
        Objects.requireNonNull(pedido);
        Objects.requireNonNull(origen);
        Map<TipoRecurso,Integer> needs = NecesidadesPedido.calcular(pedido);
        // Se valida al ingresar, antes de encolar una solicitud imposible.
        needs.forEach((type,count) -> { if(count > type.capacidad()) throw new IllegalArgumentException("pedido supera capacidad: " + type); });
        lock.lock();
        try {
            if (!running || closing) throw new IllegalStateException("motor no acepta pedidos");
            if (orders.containsKey(pedido.id())) throw new IllegalArgumentException("id de pedido duplicado");
            RuntimeOrder runtime = new RuntimeOrder(pedido, System.nanoTime(), sequence++, origen);
            orders.put(pedido.id(), runtime); pending.add(runtime);
            reevaluarStockLocked(System.nanoTime()); changed.signalAll();
        } finally { lock.unlock(); }
    }

    public void conectarInventario(Inventario inventario) {
        lock.lock();
        try {
            if (running || closing || this.inventario != null) throw new IllegalStateException("inventario debe conectarse una vez antes de iniciar");
            this.inventario=Objects.requireNonNull(inventario);
            inventario.alCambiarStock(this::reevaluarStock);
        } finally { lock.unlock(); }
    }

    public void cambiarEstrategia(EstrategiaPlanificacion nueva) {
        lock.lock();
        try {
            strategy = Objects.requireNonNull(nueva);
            // Reasigna stock de todas las órdenes aún pendientes; las ya despachadas no se interrumpen.
            reevaluarStockLocked(System.nanoTime()); changed.signalAll();
        }
        finally { lock.unlock(); }
    }

    private Map<String,Integer> necesidadesStock(Pedido pedido) {
        Map<String,Integer> needs=new HashMap<>();
        pedido.lineas().forEach(line -> needs.merge(line.productoId(),line.cantidad(),Math::addExact));
        return Map.copyOf(needs);
    }

    /** Stock se asigna explícitamente por la política vigente, y un pedido sin stock nunca ocupa worker. */
    private void reevaluarStock() {
        lock.lock(); try { reevaluarStockLocked(System.nanoTime()); changed.signalAll(); }
        finally { lock.unlock(); }
    }
    private void reevaluarStockLocked(long now) {
        if (closing || inventario == null) return;
        List<RuntimeOrder> eligible=pending.stream().filter(o ->
                o.state==EstadoPedido.ESPERANDO_STOCK || o.state==EstadoPedido.RECIBIDO).toList();
        if (eligible.isEmpty()) return;
        eligible.stream().filter(o -> o.state==EstadoPedido.RECIBIDO)
                .forEach(o -> transition(o,EstadoPedido.ESPERANDO_STOCK));
        List<PlanificadorPedidos.Candidato<RuntimeOrder>> remaining=eligible.stream().map(this::candidate).toList();
        List<Inventario.SolicitudReserva> plan=new ArrayList<>();
        while (!remaining.isEmpty()) {
            RuntimeOrder selected=planner.seleccionar(remaining,strategy,now);
            plan.add(new Inventario.SolicitudReserva(selected.pedido.id(),necesidadesStock(selected.pedido)));
            List<PlanificadorPedidos.Candidato<RuntimeOrder>> next=new ArrayList<>(remaining);
            next.removeIf(c -> c.valor()==selected); remaining=next;
        }
        Set<UUID> granted=inventario.reasignarReservas(plan);
        eligible.stream().filter(o -> granted.contains(o.pedido.id()))
                .forEach(o -> transition(o,EstadoPedido.RECIBIDO));
    }
    private PlanificadorPedidos.Candidato<RuntimeOrder> candidate(RuntimeOrder order) {
        return new PlanificadorPedidos.Candidato<>(order,order.pedido.nivelServicio().prioridad(),
                order.pedido.totalUnidades(),order.arrival,order.order);
    }

    /** Cancela una orden y devuelve al stock toda reserva anterior al consumo físico. */
    public boolean cancelar(UUID orderId) {
        lock.lock();
        try {
            RuntimeOrder order=orders.get(orderId);
            if (order==null || order.state==EstadoPedido.FINALIZADO || order.state==EstadoPedido.CANCELADO) return false;
            transition(order,EstadoPedido.CANCELADO); pending.remove(order);
            if (!order.consumed)
                inventario.liberarReserva(orderId);
            if (order.worker!=null) order.worker.interrupt();
            reevaluarStockLocked(System.nanoTime()); changed.signalAll(); return true;
        } finally { lock.unlock(); }
    }

    private void workerLoop() {
        try {
            while (true) {
                RuntimeOrder item = takeNext();
                if (item == null) return;
                try (GestorRecursos.Reserva ignored = resourceManager.adquirir(item.pedido.id().toString(),
                        NecesidadesPedido.calcular(item.pedido))) {
                    lock.lock();
                    try {
                        if (closing || item.state==EstadoPedido.CANCELADO || Thread.currentThread().isInterrupted()) {
                            cancelarSiNecesario(item,"Cancelado antes del consumo"); continue;
                        }
                        // Orden lock motor -> lock inventario; consumir no espera por recursos ni hace I/O.
                        inventario.consumirReserva(item.pedido.id()); item.consumed=true;
                        transition(item, EstadoPedido.EN_PROCESAMIENTO); item.started = System.nanoTime();
                    } finally { lock.unlock(); }
                    doWork(item);
                    lock.lock();
                    try {
                        if (closing || Thread.currentThread().isInterrupted()) transition(item, EstadoPedido.CANCELADO);
                        else { transition(item, EstadoPedido.FINALIZADO); item.finished = System.nanoTime(); }
                    } finally { lock.unlock(); }
                } catch (InterruptedException e) {
                    lock.lock(); try { cancelarSiNecesario(item,"Interrumpido antes de completar"); }
                    finally { lock.unlock(); }
                    Thread.currentThread().interrupt(); return;
                } catch (RuntimeException e) {
                    lock.lock(); try { item.error=e.getMessage(); cancelarSiNecesario(item,"Fallo: "+e.getMessage()); }
                    finally { lock.unlock(); }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cancelarSiNecesario(RuntimeOrder item,String reason) {
        if (item.state!=EstadoPedido.CANCELADO && item.state!=EstadoPedido.FINALIZADO)
            transition(item,EstadoPedido.CANCELADO);
        if (!item.consumed && inventario!=null) inventario.liberarReserva(item.pedido.id());
        if (item.error==null && reason.startsWith("Fallo:")) item.error=reason;
    }

    private RuntimeOrder takeNext() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closing) return null;
                List<RuntimeOrder> ready=pending.stream().filter(o -> o.state==EstadoPedido.RECIBIDO).toList();
                if (!ready.isEmpty()) {
                    List<PlanificadorPedidos.Candidato<RuntimeOrder>> candidates = ready.stream().map(this::candidate).toList();
                    RuntimeOrder selected = planner.seleccionar(candidates, strategy, System.nanoTime());
                    pending.remove(selected);
                    transition(selected, EstadoPedido.ESPERANDO_RECURSOS);
                    selected.worker=Thread.currentThread();
                    return selected;
                }
                changed.await();
            }
        } finally { lock.unlock(); }
    }

    private void doWork(RuntimeOrder item) throws InterruptedException {
        long duration = TimeUnit.MILLISECONDS.toNanos(simulatedTime.duracionMillis(item.pedido.totalUnidades()));
        long end = System.nanoTime() + duration;
        while (true) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return;
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private void transition(RuntimeOrder item, EstadoPedido next) {
        boolean valid = switch (item.state) {
            case ESPERANDO_STOCK -> next == EstadoPedido.RECIBIDO || next == EstadoPedido.CANCELADO;
            case RECIBIDO -> next == EstadoPedido.ESPERANDO_STOCK || next == EstadoPedido.ESPERANDO_RECURSOS
                    || next == EstadoPedido.CANCELADO;
            case ESPERANDO_RECURSOS -> next == EstadoPedido.EN_PROCESAMIENTO || next == EstadoPedido.CANCELADO;
            case EN_PROCESAMIENTO -> next == EstadoPedido.FINALIZADO || next == EstadoPedido.CANCELADO;
            default -> false;
        };
        if (!valid) throw new IllegalStateException("transición inválida " + item.state + " -> " + next);
        item.state = next;
        changed.signalAll();
    }

    public boolean esperarEstado(UUID id, EstadoPedido esperado, long timeout, TimeUnit unit) throws InterruptedException {
        long remaining=unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (remaining>0) {
                RuntimeOrder order=orders.get(id);
                if (order!=null && order.state==esperado) return true;
                remaining=changed.awaitNanos(remaining);
            }
            RuntimeOrder order=orders.get(id);
            return order!=null && order.state==esperado;
        } finally { lock.unlock(); }
    }

    public SnapshotMotor snapshot() {
        long now = System.nanoTime();
        lock.lock();
        try {
            List<EstadoPedidoMotor> orderSnapshots = orders.values().stream().map(o -> {
                double progress = o.state == EstadoPedido.FINALIZADO ? 1.0 : 0.0;
                long remaining = 0;
                if (o.state == EstadoPedido.EN_PROCESAMIENTO && o.started != null) {
                    long total = TimeUnit.MILLISECONDS.toNanos(simulatedTime.duracionMillis(o.pedido.totalUnidades()));
                    long elapsed = now - o.started; progress = Math.min(1.0, (double)elapsed/total);
                    remaining = TimeUnit.NANOSECONDS.toMillis(Math.max(0, total-elapsed));
                }
                return new EstadoPedidoMotor(o.pedido, o.state, o.arrival, o.started, o.finished, progress, remaining, o.origen);
            }).toList();
            Map<TipoRecurso, GestorRecursos.EstadoRecurso> resources = resourceManager.snapshot();
            EnumMap<TipoRecurso,Double> utilization = new EnumMap<>(TipoRecurso.class);
            long observed = Math.max(1, now-observedFrom);
            resources.forEach((type,r) -> utilization.put(type,
                    Math.min(1.0, (double)r.nanosOcupadosAcumulados()/(observed*r.capacidad()))));
            int waiting=0, processing=0, completed=0, queued=0;
            long waitNanos=0, processingNanos=0; int waitSamples=0, processingSamples=0;
            for (RuntimeOrder o : orders.values()) {
                switch(o.state) {
                    case RECIBIDO -> queued++;
                    case ESPERANDO_STOCK -> { }
                    case ESPERANDO_RECURSOS -> waiting++;
                    case EN_PROCESAMIENTO -> processing++;
                    case FINALIZADO -> completed++;
                    default -> { }
                }
                if(o.started!=null) { waitNanos += o.started-o.arrival; waitSamples++; }
                if(o.finished!=null && o.started!=null) { processingNanos += o.finished-o.started; processingSamples++; }
            }
            int waitingStock=(int)orders.values().stream().filter(o -> o.state==EstadoPedido.ESPERANDO_STOCK).count();
            EstadisticasMotor stats = new EstadisticasMotor(queued, waitingStock, waiting, processing, completed,
                    waitSamples==0?0:(double)waitNanos/waitSamples/1_000_000,
                    processingSamples==0?0:(double)processingNanos/processingSamples/1_000_000,
                    resources, utilization);
            List<RuntimeOrder> ready = pending.stream().filter(o -> o.state == EstadoPedido.RECIBIDO).toList();
            List<EntradaPlanificacion> queueView = planner.ordenar(ready.stream().map(this::candidate).toList(), strategy, now)
                    .stream().map(c -> new EntradaPlanificacion(c.valor().pedido.id(),
                            planner.prioridadEfectiva(c, now), c.unidades(), TimeUnit.NANOSECONDS.toMillis(Math.max(0, now-c.llegadaNanos())))).toList();
            return new SnapshotMotor(orderSnapshots, stats, running && !closing, strategy, queueView, now);
        } finally { lock.unlock(); }
    }

    public boolean cierreCompleto() { return workers.isTerminated() && (inventario==null || inventario.consumidorTerminado()); }
    @Override public void close() {
        lock.lock();
        try {
            if (closing) return;
            closing = true; running = false;
            for (RuntimeOrder order : pending) {
                if (order.state==EstadoPedido.RECIBIDO || order.state==EstadoPedido.ESPERANDO_STOCK) {
                    transition(order,EstadoPedido.CANCELADO);
                    if (order.state==EstadoPedido.CANCELADO && inventario!=null) inventario.liberarReserva(order.pedido.id());
                }
            }
            pending.clear(); changed.signalAll();
        } finally { lock.unlock(); }
        // Se cierra primero el productor y consumer: no entran nuevas recepciones durante el cierre.
        if (inventario!=null) inventario.close();
        workers.shutdownNow();
        boolean interrupted = false;
        try { if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch (InterruptedException e) { interrupted = true; workers.shutdownNow(); }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
