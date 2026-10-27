package gt.redxela.inventario;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.concurrencia.TipoRecurso;
import gt.redxela.modelo.EstadoRecepcion;
import gt.redxela.modelo.Producto;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Consumer de Recepción. Su lock único hace atómicas las vistas de stock y almacenamiento. */
public final class Inventario implements AutoCloseable {
    public record SolicitudReserva(UUID pedidoId, Map<String,Integer> cantidades) {
        public SolicitudReserva { Objects.requireNonNull(pedidoId); cantidades=Map.copyOf(cantidades); }
    }
    private record ReservaStock(Map<String,Integer> cantidades, boolean consumida) { }
    private final BlockingQueue<SolicitudRecepcion> queue;
    private final Recepcion recepcion;
    private final Almacen almacen;
    private final GestorRecursos recursos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String,Producto> productos = new LinkedHashMap<>();
    private final Map<UUID,ReservaStock> reservas = new HashMap<>();
    private final Set<UUID> ordenesConsumidas = new HashSet<>();
    private final Set<UUID> recepcionesProcesadas = new HashSet<>();
    private final List<SolicitudRecepcion> pendientesEspacio = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable cambioStock = () -> { };
    private Thread consumer;
    private volatile String ultimoError;

    public Inventario(BlockingQueue<SolicitudRecepcion> queue, Recepcion recepcion,
                      Almacen almacen, GestorRecursos recursos) {
        this.queue=Objects.requireNonNull(queue); this.recepcion=Objects.requireNonNull(recepcion);
        this.almacen=Objects.requireNonNull(almacen); this.recursos=Objects.requireNonNull(recursos);
        for (Producto p : CatalogoProductos.todos()) { productos.put(p.id(),p); almacen.registrarProducto(p); }
    }
    public void alCambiarStock(Runnable listener) { cambioStock=Objects.requireNonNull(listener); }
    public long capacidadTotal() { return almacen.capacidadTotal(); }
    public void iniciar() {
        if (closed.get()) throw new IllegalStateException("inventario cerrado");
        if (!started.compareAndSet(false,true)) return;
        consumer=new Thread(this::consumir,"redxela-inventario"); consumer.setDaemon(false); consumer.start();
    }

    private void consumir() {
        while (!closed.get()) {
            SolicitudRecepcion request=null;
            try {
                request=queue.take();
                try (GestorRecursos.Reserva ignored=recursos.adquirir("recepcion-"+request.id(),
                        Map.of(TipoRecurso.ESCANER,1))) {
                    recepcion.actualizar(request.id(), EstadoRecepcion.PROCESANDO,"Escaneo y registro en inventario");
                    procesar(request);
                    recepcion.completar(request.id());
                    request=null;
                }
            } catch (InterruptedException e) {
                if (request != null) {
                    // Recepción se cierra antes de interrumpir al consumer, por lo que el hueco de cola sigue libre.
                    if (!queue.offer(request)) ultimoError="No fue posible devolver a cola la recepción interrumpida: " + request.id();
                }
                Thread.currentThread().interrupt(); return;
            } catch (RuntimeException e) {
                ultimoError=e.getMessage();
                if (request != null) {
                    UUID failedId=request.id();
                    lock.lock(); try { pendientesEspacio.removeIf(p -> p.id().equals(failedId)); }
                    finally { lock.unlock(); }
                    recepcion.actualizar(request.id(),EstadoRecepcion.RECHAZADA,
                            "Error al procesar recepción: " + e.getMessage());
                    recepcion.completar(failedId);
                }
            }
        }
    }

    private void procesar(SolicitudRecepcion request) {
        if (!productos.containsKey(request.producto().id())) {
            recepcion.actualizar(request.id(),EstadoRecepcion.RECHAZADA,"Producto fuera del catálogo"); return;
        }
        lock.lock();
        try {
            if (!recepcionesProcesadas.add(request.id())) return;
            pendientesEspacio.add(request);
            reevaluarPendientesLocked();
        } finally { lock.unlock(); }
        cambioStock.run();
    }

    /** FIFO por edad; una recepción que no cabe se omite temporalmente para examinar las posteriores. */
    private boolean reevaluarPendientesLocked() {
        boolean changed=false;
        for (int i=0; i<pendientesEspacio.size();) {
            SolicitudRecepcion request=pendientesEspacio.get(i);
            Almacen.PlanAlmacenamiento plan=almacen.planificar(request.id().toString(),request.producto(),request.cantidad());
            if (plan == null) {
                recepcion.actualizar(request.id(),EstadoRecepcion.PENDIENTE_ESPACIO,"Sin capacidad compatible disponible; permanece pendiente");
                i++; continue;
            }
            almacen.aplicar(plan,request.producto());
            pendientesEspacio.remove(i);
            recepcion.actualizar(request.id(),EstadoRecepcion.APLICADA,"Mercadería ubicada y stock actualizado");
            changed=true;
        }
        return changed;
    }

    /** Reserva todas las referencias del pedido en una única sección crítica. */
    public boolean reservar(UUID orderId, Map<String,Integer> quantities) {
        Objects.requireNonNull(orderId); Objects.requireNonNull(quantities);
        Map<String,Integer> request=new LinkedHashMap<>();
        quantities.forEach((id,qty) -> {
            if (!productos.containsKey(id)) throw new IllegalArgumentException("producto fuera del catálogo: " + id);
            if (qty == null || qty <= 0) throw new IllegalArgumentException("cantidad inválida para " + id);
            request.merge(id,qty,Math::addExact);
        });
        if (request.isEmpty()) throw new IllegalArgumentException("reserva vacía");
        lock.lock();
        try {
            if (reservas.containsKey(orderId) || ordenesConsumidas.contains(orderId))
                throw new IllegalStateException("pedido ya reservado o consumido");
            Map<String,Integer> physical=almacen.stockPorProducto();
            for (var entry : request.entrySet()) {
                int reserved=reservas.values().stream().mapToInt(r -> r.cantidades().getOrDefault(entry.getKey(),0)).sum();
                if (physical.getOrDefault(entry.getKey(),0)-reserved < entry.getValue()) return false;
            }
            reservas.put(orderId,new ReservaStock(Map.copyOf(request),false)); return true;
        } finally { lock.unlock(); }
    }

    /** Reasigna todas las reservas aún pendientes en el orden decidido por el planificador. */
    public Set<UUID> reasignarReservas(List<SolicitudReserva> plan) {
        Objects.requireNonNull(plan);
        lock.lock();
        try {
            Set<UUID> revisited=new HashSet<>();
            for (SolicitudReserva request : plan) {
                if (!revisited.add(request.pedidoId())) throw new IllegalArgumentException("pedido duplicado en plan de reservas");
                reservas.remove(request.pedidoId());
            }
            Map<String,Integer> physical=almacen.stockPorProducto();
            Set<UUID> granted=new LinkedHashSet<>();
            for (SolicitudReserva request : plan) {
                for (var need : request.cantidades().entrySet()) {
                    if (!productos.containsKey(need.getKey()) || need.getValue()<=0)
                        throw new IllegalArgumentException("necesidad inválida para " + need.getKey());
                }
                boolean fits=true;
                for (var need : request.cantidades().entrySet()) {
                    int held=reservas.values().stream().mapToInt(r -> r.cantidades().getOrDefault(need.getKey(),0)).sum();
                    if (physical.getOrDefault(need.getKey(),0)-held < need.getValue()) { fits=false; break; }
                }
                if (fits) {
                    reservas.put(request.pedidoId(),new ReservaStock(request.cantidades(),false));
                    granted.add(request.pedidoId());
                }
            }
            return Collections.unmodifiableSet(granted);
        } finally { lock.unlock(); }
    }

    /** Punto físico de consumo: se llama justo al adquirir recursos e iniciar preparación. */
    public void consumirReserva(UUID orderId) {
        lock.lock();
        try {
            ReservaStock reservation=reservas.get(orderId);
            if (reservation==null || reservation.consumida()) throw new IllegalStateException("reserva no disponible");
            Map<String,Integer> physical=almacen.stockPorProducto();
            reservation.cantidades().forEach((id,count) -> {
                if (physical.getOrDefault(id,0)<count) throw new IllegalStateException("stock físico inconsistente");
            });
            Map<String,Integer> freed=new HashMap<>();
            reservation.cantidades().forEach((id,count) -> almacen.extraer(id,count,freed));
            reservas.remove(orderId); ordenesConsumidas.add(orderId);
            reevaluarPendientesLocked();
        } finally { lock.unlock(); }
        cambioStock.run();
    }

    /** Cancela una reserva no consumida y devuelve sus unidades al stock disponible. */
    public void liberarReserva(UUID orderId) {
        boolean released;
        lock.lock();
        try {
            released=reservas.remove(orderId)!=null;
        } finally { lock.unlock(); }
        if (released) cambioStock.run();
    }

    public SnapshotInventario snapshot() {
        lock.lock();
        try {
            Map<String,Integer> physical=almacen.stockPorProducto();
            List<EstadoStock> stocks=productos.values().stream().map(p -> {
                int qty=physical.getOrDefault(p.id(),0);
                int reserved=reservas.values().stream().mapToInt(r -> r.cantidades().getOrDefault(p.id(),0)).sum();
                return new EstadoStock(p.id(),p.nombre(),qty,reserved,qty-reserved);
            }).toList();
            return new SnapshotInventario(stocks,almacen.ubicaciones(),almacen.lotes(),reservas.size(),pendientesEspacio.size());
        } finally { lock.unlock(); }
    }
    public boolean consumidorTerminado() { return consumer==null || !consumer.isAlive(); }
    public String ultimoError() { return ultimoError; }

    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        recepcion.close();
        if (consumer!=null) {
            consumer.interrupt(); boolean interrupted=false;
            try { consumer.join(TimeUnit.SECONDS.toMillis(10)); }
            catch (InterruptedException e) { interrupted=true; }
            if (consumer.isAlive()) ultimoError="Timeout cerrando consumidor de inventario";
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
