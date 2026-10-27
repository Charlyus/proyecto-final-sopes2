package gt.redxela.simulacion;

import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.concurrencia.TipoRecurso;
import gt.redxela.inventario.Recepcion;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Escenario aislado y sin efectos de inventario para enseñar un ciclo real de recursos. */
public final class DemostracionDeadlock implements AutoCloseable {
    public enum Estado { DETENIDA, PREPARANDO, ESPERANDO_CICLO, CONFLICTO_CONFIRMADO, RESOLVIENDO,
        DESPLAZADA_EN_ESPERA, RECUPERANDO, LIMPIANDO, COMPLETADA, CANCELANDO, CANCELADA, ERROR }
    public enum Operacion { A, B }
    public enum EstadoOperacion { PENDIENTE, RETENIENDO_PRIMERO, SOLICITANDO_SEGUNDO, DESPLAZADA_EN_ESPERA,
        EJECUTANDO, COMPLETADA, CANCELADA }
    public record VistaOperacion(Operacion id, String proposito, EstadoOperacion estado,
                                 Map<TipoRecurso,Integer> retenidos, TipoRecurso solicitado,
                                 int unidadesSolicitadas, Set<String> propietariosEsperados) {
        public VistaOperacion { retenidos=Map.copyOf(retenidos); propietariosEsperados=Set.copyOf(propietariosEsperados); }
    }
    public record Snapshot(Estado estado, boolean activo, boolean deadlockConfirmado,
                           List<String> ciclo, List<VistaOperacion> operaciones, List<String> eventos,
                           String error, long version) {
        public Snapshot { ciclo=List.copyOf(ciclo); operaciones=List.copyOf(operaciones); eventos=List.copyOf(eventos); }
    }

    private static final String OWNER_A="Operación A · inspección";
    private static final String OWNER_B="Operación B · preparación";
    private final GestorRecursos resources; private final MotorSimulacion motor; private final Recepcion reception;
    private final long workMillis;
    private final Object monitor=new Object();
    private Estado state=Estado.DETENIDA; private volatile String session; private String error=""; private long version;
    private boolean cancelRequested; private Operacion winner;
    private List<String> cycle=List.of(); private final List<String> events=new ArrayList<>();
    private final EnumMap<Operacion,EstadoOperacion> opStates=new EnumMap<>(Operacion.class);
    private final EnumMap<Operacion,EnumMap<TipoRecurso,Integer>> held=new EnumMap<>(Operacion.class);
    private final EnumMap<Operacion,TipoRecurso> requested=new EnumMap<>(Operacion.class);
    private final EnumMap<Operacion,Set<String>> expected=new EnumMap<>(Operacion.class);
    private ExecutorService control, operationPool; private Thread controller, threadA, threadB;
    private CountDownLatch barrier, doneA, doneB, decision; private AtomicReference<Operacion> selected;
    private GestorRecursos.ModoDemostracion managerMode;
    private MotorSimulacion.PausaDemostracion motorPause;
    private Recepcion.PausaDemostracion receptionPause;

    public DemostracionDeadlock(GestorRecursos resources, MotorSimulacion motor, Recepcion reception, long workMillis) {
        this.resources=Objects.requireNonNull(resources); this.motor=Objects.requireNonNull(motor);
        this.reception=Objects.requireNonNull(reception);
        if(workMillis<0) throw new IllegalArgumentException("duración simulada negativa"); this.workMillis=workMillis;
    }

    public void iniciar() {
        synchronized(monitor) {
            if (state==Estado.PREPARANDO || state==Estado.ESPERANDO_CICLO || state==Estado.CONFLICTO_CONFIRMADO
                    || state==Estado.RESOLVIENDO || state==Estado.DESPLAZADA_EN_ESPERA || state==Estado.RECUPERANDO
                    || state==Estado.CANCELANDO) throw new IllegalStateException("Ya hay una demostración activa");
            reset(); state=Estado.PREPARANDO; event("Preparando modo aislado; no se modificarán existencias.");
            control=Executors.newSingleThreadExecutor(named("redxela-conflicto-control"));
            operationPool=Executors.newFixedThreadPool(2,named("redxela-conflicto-operacion"));
            barrier=new CountDownLatch(2); doneA=new CountDownLatch(1); doneB=new CountDownLatch(1);
            decision=new CountDownLatch(1); selected=new AtomicReference<>(); cancelRequested=false;
            control.execute(() -> { synchronized(monitor) { controller=Thread.currentThread(); }
                coordinate(); });
        }
    }

    private static ThreadFactory named(String base) {
        return new ThreadFactory() { private int n; public synchronized Thread newThread(Runnable r) { Thread t=new Thread(r,base+"-"+(++n)); t.setDaemon(false); return t; } };
    }
    private void checkCancelled() throws InterruptedException {
        if(isCancelled()||Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelación solicitada");
    }
    private void reset() {
        version++; error=""; winner=null; cycle=List.of(); events.clear();
        for (Operacion op:Operacion.values()) { opStates.put(op,EstadoOperacion.PENDIENTE); held.put(op,new EnumMap<>(TipoRecurso.class)); requested.remove(op); expected.put(op,Set.of()); }
    }
    private void event(String value) { synchronized(monitor) { events.add(value); if(events.size()>30) events.remove(0); version++; monitor.notifyAll(); } }
    private void setState(Estado next) { synchronized(monitor) { state=next; version++; monitor.notifyAll(); } }

    private void coordinate() {
        boolean interrupted=false;
        boolean completed=false;
        try {
            session="conflicto-"+UUID.randomUUID();
            receptionPause=reception.pausarParaDemostracion();
            checkCancelled();
            motorPause=motor.pausarDespachoParaDemostracion();
            checkCancelled();
            managerMode=resources.entrarModoDemostracion(session);
            checkCancelled();
            event("Recepción y despachos pausados; recursos libres confirmados atómicamente.");
            setState(Estado.ESPERANDO_CICLO);
            operationPool.submit(() -> operate(Operacion.A));
            operationPool.submit(() -> operate(Operacion.B));
            cycle=resources.esperarCiclo(session);
            synchronized(monitor) { cycle=List.copyOf(cycle); state=Estado.CONFLICTO_CONFIRMADO; event("Ciclo real confirmado: "+String.join(" → ",cycle)+"."); }
            decision.await();
            if(cancelRequested) throw new InterruptedException("cancelación solicitada");
            Operacion win=selected.get(); if(win==null) throw new InterruptedException("sin resolución");
            winner=win; setState(Estado.RESOLVIENDO);
            Operacion lose=win==Operacion.A?Operacion.B:Operacion.A;
            Thread loser=lose==Operacion.A?threadA:threadB;
            if(loser!=null) loser.interrupt();
            CountDownLatch loserDone=lose==Operacion.A?doneA:doneB;
            loserDone.await(); // la liberación ocurre en try-with-resources de la operación desplazada
            synchronized(monitor) { opStates.put(lose,EstadoOperacion.DESPLAZADA_EN_ESPERA); requested.remove(lose); expected.put(lose,Set.of()); event("Operación "+lose+" confirmó liberación y quedó en espera."); }
            (win==Operacion.A?doneA:doneB).await();
            if (isCancelled()) throw new InterruptedException("cancelación durante ejecución");
            setState(Estado.RECUPERANDO); event("Reintentando la operación desplazada con el orden global seguro.");
            recover(lose);
            completed=true; event("Ambas operaciones terminaron; restaurando los componentes normales.");
        } catch (InterruptedException ex) {
            interrupted=true;
            synchronized(monitor) { if(!cancelRequested && state!=Estado.CANCELANDO) { error=ex.getMessage(); state=Estado.LIMPIANDO; event("Escenario interrumpido: "+ex.getMessage()); } }
        } catch (Exception ex) {
            synchronized(monitor) { error=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(); state=Estado.LIMPIANDO; event("No se pudo iniciar o completar: "+error); }
        } finally {
            boolean wasCancelled;
            synchronized(monitor) { wasCancelled=cancelRequested; state=wasCancelled?Estado.CANCELANDO:Estado.LIMPIANDO; version++; monitor.notifyAll(); }
            if(operationPool!=null) operationPool.shutdownNow();
            boolean prior=Thread.interrupted();
            try { if(operationPool!=null && !operationPool.awaitTermination(5,TimeUnit.SECONDS)) error="Operaciones de demostración no terminaron dentro del límite"; }
            catch (InterruptedException ex) { prior=true; Thread.interrupted(); }
            closeQuietly(managerMode); managerMode=null;
            closeQuietly(motorPause); motorPause=null;
            closeQuietly(receptionPause); receptionPause=null;
            if(control!=null) control.shutdown();
            synchronized(monitor) {
                if(cancelRequested) { state=Estado.CANCELADA; event("Demostración cancelada y limpieza confirmada."); }
                else state=completed?Estado.COMPLETADA:Estado.ERROR;
                version++; monitor.notifyAll();
            }
            if(prior||interrupted) Thread.currentThread().interrupt();
        }
    }

    private void operate(Operacion op) {
        synchronized(monitor) { if(op==Operacion.A) threadA=Thread.currentThread(); else threadB=Thread.currentThread(); }
        String owner=op==Operacion.A?OWNER_A:OWNER_B;
        TipoRecurso first=op==Operacion.A?TipoRecurso.ESCANER:TipoRecurso.MONTACARGAS;
        TipoRecurso second=op==Operacion.A?TipoRecurso.MONTACARGAS:TipoRecurso.ESCANER;
        int firstCount=op==Operacion.A?1:TipoRecurso.MONTACARGAS.capacidad();
        CountDownLatch done=op==Operacion.A?doneA:doneB;
        try {
            EnumMap<TipoRecurso,Integer> need=new EnumMap<>(TipoRecurso.class); need.put(first,firstCount);
            try (GestorRecursos.Reserva one=resources.adquirirDemostracion(session,"demo-"+op+"-primero",owner,need)) {
                synchronized(monitor) { held.get(op).merge(first,firstCount,Integer::sum); opStates.put(op,EstadoOperacion.RETENIENDO_PRIMERO); event("Operación "+op+" retuvo "+first+" × "+firstCount+"."); }
                barrier.countDown(); barrier.await();
                synchronized(monitor) { requested.put(op,second); expected.put(op,Set.of(op==Operacion.A?OWNER_B:OWNER_A)); opStates.put(op,EstadoOperacion.SOLICITANDO_SEGUNDO); event("Operación "+op+" solicita "+second+"."); }
                need=new EnumMap<>(TipoRecurso.class); need.put(second,1);
                try (GestorRecursos.Reserva two=resources.adquirirDemostracion(session,"demo-"+op+"-segundo",owner,need)) {
                    synchronized(monitor) { held.get(op).merge(second,1,Integer::sum); requested.remove(op); opStates.put(op,EstadoOperacion.EJECUTANDO); event("Operación "+op+" obtuvo ambos recursos y ejecuta trabajo simulado."); }
                    TimeUnit.MILLISECONDS.sleep(workMillis);
                    event("Operación "+op+" terminó su trabajo simulado; liberando reservas.");
                }
            }
            synchronized(monitor) { opStates.put(op,EstadoOperacion.COMPLETADA); held.get(op).clear(); event("Operación "+op+" completó y liberó sus recursos."); }
        } catch (InterruptedException ex) {
            synchronized(monitor) {
                held.get(op).clear(); requested.remove(op); expected.put(op,Set.of());
                opStates.put(op,cancelRequested?EstadoOperacion.CANCELADA:EstadoOperacion.DESPLAZADA_EN_ESPERA);
                event(cancelRequested?"Operación "+op+" interrumpida por cancelación.":"Operación "+op+" interrumpida; sus reservas se liberaron en finally.");
            }
            Thread.currentThread().interrupt();
        } finally { done.countDown(); }
    }

    private void recover(Operacion op) throws InterruptedException {
        String owner=op==Operacion.A?OWNER_A:OWNER_B;
        EnumMap<TipoRecurso,Integer> needs=new EnumMap<>(TipoRecurso.class);
        needs.put(TipoRecurso.ESCANER,1); needs.put(TipoRecurso.MONTACARGAS,1);
        synchronized(monitor) { opStates.put(op,EstadoOperacion.RETENIENDO_PRIMERO); }
        try (GestorRecursos.Reserva ignored=resources.adquirirDemostracion(session,"demo-"+op+"-reintento",owner,needs)) {
            synchronized(monitor) { held.get(op).putAll(needs); opStates.put(op,EstadoOperacion.EJECUTANDO); event("Operación "+op+" reintentada con adquisición ordenada."); }
            TimeUnit.MILLISECONDS.sleep(workMillis);
        } catch (InterruptedException ex) {
            synchronized(monitor) { held.get(op).clear(); opStates.put(op,EstadoOperacion.CANCELADA); }
            throw ex;
        }
        synchronized(monitor) { held.get(op).clear(); opStates.put(op,EstadoOperacion.COMPLETADA); event("Operación desplazada "+op+" completó sin efectos duplicables."); }
    }

    public boolean resolver(Operacion op) {
        Objects.requireNonNull(op);
        synchronized(monitor) {
            if(state!=Estado.CONFLICTO_CONFIRMADO || !selected.compareAndSet(null,op)) return false;
            winner=op; state=Estado.RESOLVIENDO; event("Resolución manual: continúa operación "+op+"."); decision.countDown(); return true;
        }
    }
    public void cancelar() {
        synchronized(monitor) {
            if(!active(state)) return;
            cancelRequested=true; state=Estado.CANCELANDO; event("Cancelación solicitada; esperando liberación controlada.");
            if(decision!=null) decision.countDown();
            if(threadA!=null) threadA.interrupt(); if(threadB!=null) threadB.interrupt();
            if(controller!=null) controller.interrupt();
        }
    }
    private boolean isCancelled() { synchronized(monitor) { return cancelRequested; } }
    private static boolean active(Estado s) { return switch(s) { case PREPARANDO,ESPERANDO_CICLO,CONFLICTO_CONFIRMADO,RESOLVIENDO,DESPLAZADA_EN_ESPERA,RECUPERANDO,LIMPIANDO,CANCELANDO -> true; default -> false; }; }
    public Snapshot snapshot() {
        GestorRecursos.SnapshotCompleto resourceSnapshot=resources.snapshotCompleto();
        List<String> actualCycle=resourceSnapshot.sesionDemostracion()!=null
                &&resourceSnapshot.sesionDemostracion().equals(session)?resourceSnapshot.ciclo():List.of();
        synchronized(monitor) {
            List<VistaOperacion> ops=new ArrayList<>();
            for(Operacion op:Operacion.values()) ops.add(new VistaOperacion(op,op==Operacion.A?"Inspección: escáner → montacargas":"Preparación: montacargas → escáner",
                    opStates.getOrDefault(op,EstadoOperacion.PENDIENTE),held.getOrDefault(op,new EnumMap<>(TipoRecurso.class)),
                    requested.get(op),requested.containsKey(op)?1:0,expected.getOrDefault(op,Set.of())));
            return new Snapshot(state,active(state),!actualCycle.isEmpty(),
                    actualCycle,ops,events,error,version);
        }
    }
    public boolean esperarEstado(Estado expected, long timeout, TimeUnit unit) throws InterruptedException {
        long until=System.nanoTime()+unit.toNanos(timeout); synchronized(monitor) {
            while(state!=expected) { long left=until-System.nanoTime(); if(left<=0)return false; TimeUnit.NANOSECONDS.timedWait(monitor,left); } return true;
        }
    }
    public boolean esperarTerminacion(long timeout,TimeUnit unit) throws InterruptedException {
        long until=System.nanoTime()+unit.toNanos(timeout); synchronized(monitor) {
            while(active(state)) { long left=until-System.nanoTime(); if(left<=0)return false; TimeUnit.NANOSECONDS.timedWait(monitor,left); } return true;
        }
    }
    public boolean hilosTerminados() { ExecutorService c=control,o=operationPool; return (c==null||c.isTerminated())&&(o==null||o.isTerminated()); }
    public boolean esperarHilos(long timeout,TimeUnit unit) throws InterruptedException {
        long deadline=System.nanoTime()+unit.toNanos(timeout); ExecutorService o=operationPool,c=control;
        if(o!=null) { long left=deadline-System.nanoTime(); if(left<=0||!o.awaitTermination(left,TimeUnit.NANOSECONDS)) return false; }
        if(c!=null) { long left=deadline-System.nanoTime(); if(left<=0||!c.awaitTermination(left,TimeUnit.NANOSECONDS)) return false; }
        return true;
    }
    private static void closeQuietly(AutoCloseable closeable) { if(closeable!=null) try { closeable.close(); } catch(Exception ignored) { } }
    @Override public void close() {
        cancelar(); boolean interrupted=false;
        try { esperarTerminacion(10,TimeUnit.SECONDS); } catch(InterruptedException e) { interrupted=true; }
        ExecutorService c=control,o=operationPool;
        if(o!=null) { o.shutdownNow(); try{o.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;} }
        if(c!=null) { c.shutdownNow(); try{c.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;} }
        if(interrupted) Thread.currentThread().interrupt();
    }
}
