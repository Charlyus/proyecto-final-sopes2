package gt.redxela.concurrencia;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Adquiere siempre en orden enum; la reserva es idempotente y AutoCloseable para liberar en finally/try. */
public final class GestorRecursos {
    public record EstadoRecurso(TipoRecurso tipo, int capacidad, int disponibles,
                                Map<String, Integer> operacionesPropietarias,
                                long nanosOcupadosAcumulados) {
        public EstadoRecurso { operacionesPropietarias = Map.copyOf(operacionesPropietarias); }
        public int ocupados() { return capacidad - disponibles; }
    }
    private static final class Pool {
        final Semaphore semaphore;
        final int capacity;
        final Map<String, Integer> owners = new HashMap<>();
        int available;
        long occupiedNanos;
        long lastChange = System.nanoTime();
        Pool(int capacity) { this.capacity = capacity; available = capacity; semaphore = new Semaphore(capacity, true); }
        void update() { long now = System.nanoTime(); occupiedNanos += (long)(capacity - available) * (now-lastChange); lastChange = now; }
    }
    private final EnumMap<TipoRecurso, Pool> pools = new EnumMap<>(TipoRecurso.class);
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Set<String> activeOperations = new HashSet<>();

    public GestorRecursos() { for (TipoRecurso t : TipoRecurso.values()) pools.put(t, new Pool(t.capacidad())); }

    public Reserva adquirir(String operationId, Map<TipoRecurso, Integer> needs) throws InterruptedException {
        Objects.requireNonNull(operationId); Objects.requireNonNull(needs);
        EnumMap<TipoRecurso,Integer> request = new EnumMap<>(TipoRecurso.class);
        needs.forEach((type, count) -> {
            Objects.requireNonNull(type); Objects.requireNonNull(count);
            if (count < 0 || count > type.capacidad()) throw new IllegalArgumentException("necesidad excede capacidad: " + type);
            if (count > 0) request.put(type, count);
        });
        stateLock.lockInterruptibly();
        try { if (!activeOperations.add(operationId)) throw new IllegalStateException("operación ya reservada: " + operationId); }
        finally { stateLock.unlock(); }
        List<TipoRecurso> acquired = new ArrayList<>();
        try {
            // El orden global impide ciclos de espera aunque se reserven varias clases.
            for (TipoRecurso type : TipoRecurso.values()) {
                int count = request.getOrDefault(type, 0);
                if (count == 0) continue;
                pools.get(type).semaphore.acquire(count);
                acquired.add(type);
                stateLock.lock();
                try {
                    Pool pool = pools.get(type); pool.update(); pool.available -= count;
                    pool.owners.put(operationId, count);
                } finally { stateLock.unlock(); }
            }
            return new Reserva(operationId, request);
        } catch (InterruptedException | RuntimeException e) {
            release(operationId, acquired);
            throw e;
        }
    }

    private void release(String id, Collection<TipoRecurso> acquired) {
        stateLock.lock();
        try {
            for (TipoRecurso type : acquired) {
                Pool pool = pools.get(type); Integer count = pool.owners.remove(id);
                if (count != null) { pool.update(); pool.available += count; pool.semaphore.release(count); }
            }
            activeOperations.remove(id);
        } finally { stateLock.unlock(); }
    }

    public Map<TipoRecurso, EstadoRecurso> snapshot() {
        stateLock.lock();
        try {
            EnumMap<TipoRecurso, EstadoRecurso> result = new EnumMap<>(TipoRecurso.class);
            pools.forEach((type, pool) -> {
                pool.update();
                result.put(type, new EstadoRecurso(type, pool.capacity, pool.available,
                        pool.owners, pool.occupiedNanos));
            });
            return Collections.unmodifiableMap(result);
        } finally { stateLock.unlock(); }
    }

    public final class Reserva implements AutoCloseable {
        private final String id;
        private final List<TipoRecurso> acquired;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Reserva(String id, Map<TipoRecurso,Integer> needs) {
            this.id = id; this.acquired = new ArrayList<>(needs.keySet());
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) release(id, acquired); }
    }
}
