package gt.redxela.simulacion;

import gt.redxela.inventario.CatalogoProductos;
import gt.redxela.modelo.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Generador de un solo hilo programado; un fallo de envío se informa y el siguiente tick continúa. */
public final class GeneradorPedidos implements AutoCloseable {
    public record Configuracion(long intervaloMillis, int unidadesMinimas, int unidadesMaximas, long semilla) {
        public Configuracion {
            if (intervaloMillis < 1) throw new IllegalArgumentException("intervalo debe ser al menos 1 ms");
            if (unidadesMinimas < 1 || unidadesMaximas < unidadesMinimas)
                throw new IllegalArgumentException("rango de unidades inválido");
        }
    }
    private final MotorSimulacion motor;
    private final RegistroClientes clientes;
    private final Consumer<String> errores;
    private final Consumer<Pedido> aceptadoListener;
    private final EnumMap<NivelServicio, LongAdder> porNivel = new EnumMap<>(NivelServicio.class);
    private final LongAdder aceptados = new LongAdder();
    private ScheduledExecutorService scheduler;
    private boolean cerrado;
    private boolean activo;

    public GeneradorPedidos(MotorSimulacion motor, RegistroClientes clientes, Consumer<String> errores) {
        this(motor, clientes, errores, pedido -> { });
    }
    public GeneradorPedidos(MotorSimulacion motor, RegistroClientes clientes, Consumer<String> errores,
                            Consumer<Pedido> aceptadoListener) {
        this.motor = Objects.requireNonNull(motor); this.clientes = Objects.requireNonNull(clientes);
        this.errores = Objects.requireNonNull(errores); this.aceptadoListener = Objects.requireNonNull(aceptadoListener);
        for (NivelServicio level : NivelServicio.values()) porNivel.put(level, new LongAdder());
    }
    public synchronized boolean iniciar(Configuracion config) {
        Objects.requireNonNull(config);
        if (cerrado) throw new IllegalStateException("generador cerrado");
        if (activo || scheduler != null) return false;
        DataSequence data = new DataSequence(config, clientes);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redxela-generador-pedidos"); t.setDaemon(false); return t;
        });
        activo = true;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Pedido pedido = data.siguiente();
                motor.enviar(pedido, OrigenPedido.AUTOMATICO);
                aceptados.increment(); porNivel.get(pedido.nivelServicio()).increment();
                try { aceptadoListener.accept(pedido); } catch (RuntimeException listenerError) { errores.accept("Observador de pedidos: " + listenerError.getMessage()); }
            } catch (RuntimeException e) {
                try { errores.accept("Generador: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                catch (RuntimeException listenerError) { /* un observador defectuoso no debe matar la tarea programada */ }
            }
        }, 0, config.intervaloMillis(), TimeUnit.MILLISECONDS);
        return true;
    }
    /** Espera fuera del EDT: al retornar no quedan ticks que puedan enviar pedidos. */
    public void detener() {
        ScheduledExecutorService actual;
        synchronized (this) {
            if (scheduler == null) return;
            activo = false; actual = scheduler; actual.shutdown();
        }
        boolean interrupted = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!actual.isTerminated() && System.nanoTime() < deadline) {
                try { actual.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; actual.shutdownNow(); }
            }
            if (!actual.isTerminated()) {
                actual.shutdownNow();
                long forceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!actual.isTerminated() && System.nanoTime() < forceDeadline) {
                    try { actual.awaitTermination(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (!actual.isTerminated()) throw new IllegalStateException("el generador no terminó dentro del límite");
            }
        } finally {
            synchronized (this) { if (scheduler == actual) scheduler = null; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    public synchronized boolean activo() { return activo; }
    public long aceptados() { return aceptados.sum(); }
    public Map<NivelServicio, Long> distribucion() {
        EnumMap<NivelServicio, Long> copy = new EnumMap<>(NivelServicio.class);
        porNivel.forEach((nivel, count) -> copy.put(nivel, count.sum())); return Map.copyOf(copy);
    }
    public synchronized boolean terminado() { return scheduler == null || scheduler.isTerminated(); }
    @Override public void close() { synchronized (this) { cerrado = true; } detener(); }

    /** Secuencia reproducible: niveles en ciclo uniforme; los demás campos usan la semilla configurada. */
    static final class DataSequence {
        private final Configuracion config; private final Random random;
        private final Map<NivelServicio, Cliente> clientes;
        private int index;
        DataSequence(Configuracion config, RegistroClientes registry) {
            this.config = config; random = new Random(config.semilla());
            EnumMap<NivelServicio, Cliente> map = new EnumMap<>(NivelServicio.class);
            for (NivelServicio level : NivelServicio.values()) {
                String email = "generador." + level.name().toLowerCase(Locale.ROOT) + "@redxela.local";
                Cliente client = null;
                for (int suffix = 0; client == null; suffix++) {
                    String candidateEmail = suffix == 0 ? email : "generador." + level.name().toLowerCase(Locale.ROOT)
                            + "." + suffix + "@redxela.local";
                    Optional<Cliente> existing = registry.buscarCorreo(candidateEmail);
                    if (existing.isEmpty()) client = registry.registrar("[Sintético] Generador " + level.etiqueta(), candidateEmail, level);
                    else if (existing.get().nombre().startsWith("[Sintético]") && existing.get().nivelServicio() == level)
                        client = existing.get();
                }
                map.put(level, client);
            }
            clientes = Map.copyOf(map);
        }
        Pedido siguiente() {
            NivelServicio[] levels = NivelServicio.values();
            NivelServicio level = levels[index++ % levels.length];
            long range = (long) config.unidadesMaximas() - config.unidadesMinimas() + 1;
            int total = (int) (config.unidadesMinimas() + random.nextLong(range));
            List<Producto> products = new ArrayList<>(CatalogoProductos.todos());
            Collections.shuffle(products, random);
            int count = Math.min(total, 1 + random.nextInt(Math.min(3, products.size())));
            List<LineaPedido> lines = new ArrayList<>();
            for (int i = 0, remaining = total; i < count; i++) {
                int quantity = i == count - 1 ? remaining : 1 + random.nextInt(remaining - (count - i - 1));
                remaining -= quantity; lines.add(new LineaPedido(products.get(i), quantity));
            }
            return new Pedido(clientes.get(level), level, lines);
        }
    }
}
