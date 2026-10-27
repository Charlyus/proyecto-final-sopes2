package gt.redxela.concurrencia;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Administra los permisos reales y su registro de propietarios bajo un único lock. */
public final class GestorRecursos {
    public record EstadoRecurso(TipoRecurso tipo, int capacidad, int disponibles,
                                Map<String, Integer> operacionesPropietarias,
                                long nanosOcupadosAcumulados) {
        public EstadoRecurso { operacionesPropietarias = Map.copyOf(operacionesPropietarias); }
        public int ocupados() { return capacidad - disponibles; }
    }
    public record SolicitudEnEspera(String operacion, TipoRecurso recurso, int unidades,
                                    Set<String> propietariosEsperados) {
        public SolicitudEnEspera { propietariosEsperados = Set.copyOf(propietariosEsperados); }
    }
    public record SnapshotCompleto(Map<TipoRecurso, EstadoRecurso> recursos,
                                   Map<String, SolicitudEnEspera> esperas,
                                   List<String> ciclo, boolean modoDemostracionActivo,
                                   String sesionDemostracion) {
        public SnapshotCompleto {
            recursos = Map.copyOf(recursos); esperas = Map.copyOf(esperas); ciclo = List.copyOf(ciclo);
        }
    }

    private static final class Pool {
        final TipoRecurso tipo;
        final int capacidad;
        final Semaphore semaphore;
        final Map<String, Integer> owners = new LinkedHashMap<>();
        int disponibles;
        long acumulado;
        long cambio = System.nanoTime();
        Pool(TipoRecurso tipo) { this.tipo = tipo; capacidad = tipo.capacidad(); disponibles = capacidad; semaphore = new Semaphore(capacidad, true); }
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final EnumMap<TipoRecurso, Pool> pools = new EnumMap<>(TipoRecurso.class);
    private final Map<String, SolicitudEnEspera> waiting = new LinkedHashMap<>();
    private final Set<String> leases = new HashSet<>();
    private String demoSession;

    public GestorRecursos() { for (TipoRecurso type : TipoRecurso.values()) pools.put(type, new Pool(type)); }

    public Reserva adquirir(String operacion, Map<TipoRecurso, Integer> necesidades) throws InterruptedException {
        return acquire(operacion, operacion, necesidades, null);
    }

    /** Uso exclusivo del escenario: se permite adquirir por etapas y en orden inverso. */
    public Reserva adquirirDemostracion(String sesion, String leaseId, String operacion,
                                        Map<TipoRecurso, Integer> necesidades) throws InterruptedException {
        return acquire(leaseId, operacion, necesidades, Objects.requireNonNull(sesion));
    }

    private Reserva acquire(String leaseId, String owner, Map<TipoRecurso, Integer> needs,
                            String session) throws InterruptedException {
        Objects.requireNonNull(leaseId); Objects.requireNonNull(owner); Objects.requireNonNull(needs);
        EnumMap<TipoRecurso, Integer> requested = new EnumMap<>(TipoRecurso.class);
        for (var need : needs.entrySet()) {
            TipoRecurso type=need.getKey(); Integer count=need.getValue();
            if (type == null || count == null || count < 0 || count > type.capacidad()) throw new IllegalArgumentException("Necesidad inválida de recursos");
            if (count > 0) requested.put(type, count);
        }
        // EnumMap itera según el orden del enum; toda adquisición normal compuesta
        // respeta así un orden global. La demostración invierte orden adquiriendo en etapas.
        EnumMap<TipoRecurso, Integer> acquired = new EnumMap<>(TipoRecurso.class);
        lock.lockInterruptibly();
        try {
            if (leases.contains(leaseId)) throw new IllegalStateException("La operación ya tiene una reserva activa: " + leaseId);
            if (session != null && !session.equals(demoSession)) throw new IllegalStateException("La sesión de demostración ya no está activa");
            while (demoSession != null && !demoSession.equals(session)) changed.await();
            leases.add(leaseId);
            for (var entry : requested.entrySet()) {
                TipoRecurso type = entry.getKey(); int count = entry.getValue(); Pool pool = pools.get(type);
                while (pool.disponibles < count) {
                    Set<String> expected = new LinkedHashSet<>(pool.owners.keySet()); expected.remove(owner);
                    waiting.put(owner, new SolicitudEnEspera(owner, type, count, expected)); changed.signalAll();
                    changed.await();
                    if (session != null && !session.equals(demoSession)) throw new InterruptedException("Demostración cancelada");
                }
                if (!pool.semaphore.tryAcquire(count, 0, TimeUnit.NANOSECONDS)) throw new IllegalStateException("Desfase entre permisos y registro de recursos");
                updateOccupied(pool); pool.disponibles -= count; pool.owners.merge(owner, count, Integer::sum);
                acquired.put(type, count); waiting.remove(owner); changed.signalAll();
            }
            waiting.remove(owner); changed.signalAll();
            return new Reserva(leaseId, owner, acquired);
        } catch (InterruptedException | RuntimeException ex) {
            releasePartial(leaseId, owner, acquired); waiting.remove(owner); leases.remove(leaseId); changed.signalAll();
            throw ex;
        } finally { lock.unlock(); }
    }

    private void updateOccupied(Pool p) {
        long now=System.nanoTime();
        p.acumulado+=(long)(p.capacidad-p.disponibles)*(now-p.cambio);
        p.cambio=now;
    }
    private void releasePartial(String lease, String owner, Map<TipoRecurso, Integer> held) {
        if (!leases.contains(lease)) return;
        held.forEach((type, count) -> {
            Pool p = pools.get(type); updateOccupied(p);
            int owned = p.owners.getOrDefault(owner, 0); int give = Math.min(count, owned);
            if (give > 0) { if (owned == give) p.owners.remove(owner); else p.owners.put(owner, owned - give); p.disponibles += give; p.semaphore.release(give); }
        });
        leases.remove(lease); changed.signalAll();
    }

    public Map<TipoRecurso, EstadoRecurso> snapshot() { return snapshotCompleto().recursos(); }
    public SnapshotCompleto snapshotCompleto() {
        lock.lock(); try {
            EnumMap<TipoRecurso, EstadoRecurso> states = new EnumMap<>(TipoRecurso.class); long now = System.nanoTime();
            pools.forEach((type, p) -> states.put(type, new EstadoRecurso(type, p.capacidad, p.disponibles, p.owners,
                    p.acumulado + (long)(p.capacidad-p.disponibles)*(now-p.cambio))));
            return new SnapshotCompleto(states, waiting, findCycle(waiting), demoSession != null, demoSession);
        } finally { lock.unlock(); }
    }

    public ModoDemostracion entrarModoDemostracion(String session) throws InterruptedException {
        Objects.requireNonNull(session); lock.lockInterruptibly();
        try {
            if (demoSession != null) throw new IllegalStateException("Ya hay una demostración activa");
            if (!leases.isEmpty() || !waiting.isEmpty() || pools.values().stream().anyMatch(p -> p.disponibles != p.capacidad || !p.owners.isEmpty()))
                throw new IllegalStateException("Los recursos deben estar libres y sin operaciones activas");
            demoSession = session; changed.signalAll(); return new ModoDemostracion(session);
        } finally { lock.unlock(); }
    }

    /** Espera sobre la condición que registra propietarios y solicitudes; no consulta en bucle. */
    public List<String> esperarCiclo(String session) throws InterruptedException {
        lock.lockInterruptibly(); try {
            while (Objects.equals(demoSession, session)) {
                List<String> cycle = findCycle(waiting); if (!cycle.isEmpty()) return cycle;
                changed.await();
            }
            throw new InterruptedException("La sesión de demostración terminó");
        } finally { lock.unlock(); }
    }

    public boolean esperarSolicitud(String operacion, long timeout, TimeUnit unit) throws InterruptedException {
        long remaining=unit.toNanos(timeout); lock.lockInterruptibly();
        try {
            while (remaining>0 && !waiting.containsKey(operacion)) remaining=changed.awaitNanos(remaining);
            return waiting.containsKey(operacion);
        } finally { lock.unlock(); }
    }

    public boolean esperarTodoLibre(long timeout,TimeUnit unit) throws InterruptedException {
        long remaining=unit.toNanos(timeout); lock.lockInterruptibly();
        try {
            while(remaining>0) {
                boolean free=leases.isEmpty()&&waiting.isEmpty()&&pools.values().stream().allMatch(p->p.disponibles==p.capacidad);
                if(free) return true;
                remaining=changed.awaitNanos(remaining);
            }
            return leases.isEmpty()&&waiting.isEmpty()&&pools.values().stream().allMatch(p->p.disponibles==p.capacidad);
        } finally { lock.unlock(); }
    }

    private static List<String> findCycle(Map<String, SolicitudEnEspera> waits) {
        for (String start : waits.keySet()) {
            List<String> path = new ArrayList<>(); Set<String> visiting = new HashSet<>();
            if (dfs(start, start, waits, path, visiting)) return List.copyOf(path);
        }
        return List.of();
    }
    private static boolean dfs(String current, String target, Map<String, SolicitudEnEspera> waits, List<String> path, Set<String> visiting) {
        if (!visiting.add(current)) return false; path.add(current);
        SolicitudEnEspera wait = waits.get(current);
        if (wait != null) for (String owner : wait.propietariosEsperados()) {
            if (owner.equals(target)) { path.add(target); return true; }
            if (waits.containsKey(owner) && dfs(owner, target, waits, path, visiting)) return true;
        }
        path.remove(path.size() - 1); visiting.remove(current); return false;
    }

    public final class Reserva implements AutoCloseable {
        private final String lease, owner; private final EnumMap<TipoRecurso, Integer> held; private boolean closed;
        private Reserva(String lease, String owner, Map<TipoRecurso, Integer> held) { this.lease = lease; this.owner = owner; this.held = new EnumMap<>(TipoRecurso.class); this.held.putAll(held); }
        @Override public void close() { lock.lock(); try { if (!closed) { closed = true; releasePartial(lease, owner, held); } } finally { lock.unlock(); } }
    }
    public final class ModoDemostracion implements AutoCloseable {
        private final String session; private boolean closed;
        private ModoDemostracion(String session) { this.session = session; }
        @Override public void close() {
            lock.lock(); try {
                if (closed) return;
                if (!Objects.equals(demoSession, session)) { closed = true; return; }
                if (!leases.isEmpty()) throw new IllegalStateException("No se puede restaurar el modo mientras haya recursos retenidos");
                demoSession = null; closed = true; changed.signalAll();
            } finally { lock.unlock(); }
        }
    }
}
