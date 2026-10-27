package gt.redxela.inventario;

import gt.redxela.modelo.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Productor no bloqueante: la saturación se devuelve al llamador como rechazo explícito. */
public final class Recepcion implements AutoCloseable {
    private final BlockingQueue<SolicitudRecepcion> queue;
    private final long capacidadTotal;
    private final Semaphore espaciosEnProceso;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, EstadoRecepcionVista> records = new LinkedHashMap<>();
    private final Set<UUID> solicitudesSinFinalizar = new HashSet<>();
    private boolean demonstrationPaused;

    public Recepcion(BlockingQueue<SolicitudRecepcion> queue, long capacidadTotal) {
        this.queue=Objects.requireNonNull(queue);
        if (queue.remainingCapacity()==Integer.MAX_VALUE)
            throw new IllegalArgumentException("la cola de recepción debe tener capacidad acotada");
        espaciosEnProceso=new Semaphore(queue.remainingCapacity());
        if (capacidadTotal <= 0) throw new IllegalArgumentException("capacidad total debe ser positiva");
        this.capacidadTotal=capacidadTotal;
    }

    public synchronized EstadoRecepcionVista recibir(Producto producto, int cantidad) {
        Objects.requireNonNull(producto);
        if (cantidad <= 0) throw new IllegalArgumentException("cantidad debe ser positiva");
        UUID id=UUID.randomUUID();
        EstadoRecepcion estado=EstadoRecepcion.EN_COLA; String motivo="Aceptada en cola";
        long volumen=Math.multiplyExact((long)producto.volumenPorUnidad(), cantidad);
        if (volumen > capacidadTotal) {
            estado=EstadoRecepcion.RECHAZADA; motivo="Excede la capacidad total del almacén";
        } else if (demonstrationPaused) {
            estado=EstadoRecepcion.RECHAZADA; motivo="Recepción temporalmente pausada por demostración de conflictos";
        } else if (closed.get()) {
            estado=EstadoRecepcion.RECHAZADA; motivo="Recepción cerrada";
        }
        EstadoRecepcionVista view=new EstadoRecepcionVista(id, producto.id(), producto.nombre(), cantidad, estado, motivo);
        records.put(id,view);
        if (estado == EstadoRecepcion.EN_COLA && !espaciosEnProceso.tryAcquire()) {
            view=new EstadoRecepcionVista(id,producto.id(),producto.nombre(),cantidad,
                    EstadoRecepcion.RECHAZADA_COLA_LLENA,"Cola de recepción saturada; solicitud no aceptada");
            records.put(id,view);
        } else if (estado == EstadoRecepcion.EN_COLA) {
            solicitudesSinFinalizar.add(id);
            if (!queue.offer(new SolicitudRecepcion(id,producto,cantidad,System.nanoTime()))) {
                solicitudesSinFinalizar.remove(id); espaciosEnProceso.release();
            view=new EstadoRecepcionVista(id,producto.id(),producto.nombre(),cantidad,
                    EstadoRecepcion.RECHAZADA_COLA_LLENA,"Cola llena; solicitud no aceptada");
            records.put(id,view);
            }
        }
        return view;
    }

    synchronized void actualizar(UUID id, EstadoRecepcion estado, String motivo) {
        EstadoRecepcionVista old=records.get(id);
        if (old == null) return; // Mensajes inyectados fuera de Recepción no pertenecen a su vista.
        records.put(id,new EstadoRecepcionVista(id,old.productoId(),old.productoNombre(),old.cantidad(),estado,motivo));
        notifyAll();
    }
    synchronized void completar(UUID id) {
        if (solicitudesSinFinalizar.remove(id)) espaciosEnProceso.release();
    }
    public synchronized List<EstadoRecepcionVista> snapshot() { return List.copyOf(records.values()); }
    public synchronized EstadoRecepcionVista estado(UUID id) { return records.get(id); }

    /** Pausa atómica del productor; no bloquea el EDT ni toca solicitudes ya aceptadas. */
    public synchronized PausaDemostracion pausarParaDemostracion() {
        if (closed.get()) throw new IllegalStateException("Recepción cerrada");
        if (demonstrationPaused) throw new IllegalStateException("Recepción ya pausada");
        demonstrationPaused = true;
        return new PausaDemostracion();
    }
    public final class PausaDemostracion implements AutoCloseable {
        private boolean released;
        private PausaDemostracion() { }
        @Override public void close() { synchronized (Recepcion.this) { if (!released) { demonstrationPaused=false; released=true; } } }
    }
    public synchronized EstadoRecepcionVista esperarEstado(UUID id, EstadoRecepcion esperado, long timeout,
                                                            java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        while (true) {
            EstadoRecepcionVista value=records.get(id);
            if (value==null || value.estado()==esperado) return value;
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) return value;
            long millis=remaining/1_000_000; int nanos=(int)(remaining%1_000_000);
            wait(millis,nanos);
        }
    }
    @Override public synchronized void close() { closed.set(true); }
}
