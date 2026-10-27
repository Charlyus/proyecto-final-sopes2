package gt.redxela.concurrencia;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Convierte transcurrido monotónico real a tiempo de simulación. Una instancia
 * fija el factor al crear cada ejecución; no cambia la duración real del trabajo.
 */
public final class EscalaTiempoSimulado {
    public static final String PROPIEDAD = "redxela.escalaTiempo";
    private final double factor;
    private final LongSupplier fuenteMonotonica;
    private final long origenRealNanos;

    public EscalaTiempoSimulado(double factor) { this(factor, System::nanoTime); }

    /** La fuente inyectable permite probar conversiones y objetivos sin demoras reales. */
    public EscalaTiempoSimulado(double factor, LongSupplier fuenteMonotonica) {
        if (!Double.isFinite(factor) || factor < 0.01 || factor > 1000)
            throw new IllegalArgumentException("la escala debe estar entre 0.01 y 1000");
        this.factor = factor;
        this.fuenteMonotonica = Objects.requireNonNull(fuenteMonotonica);
        this.origenRealNanos = fuenteMonotonica.getAsLong();
    }

    public static EscalaTiempoSimulado configurada() {
        String raw = System.getProperty(PROPIEDAD);
        if (raw == null || raw.isBlank()) raw = System.getenv().getOrDefault("REDXELA_ESCALA_TIEMPO", "1.0");
        try { return new EscalaTiempoSimulado(Double.parseDouble(raw)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Propiedad " + PROPIEDAD + " debe ser numérica", e); }
    }

    public double factor() { return factor; }
    public long ahoraNanos() { return aTiempoSimuladoNanos(fuenteMonotonica.getAsLong()); }
    public long aTiempoSimuladoNanos(long instanteRealNanos) {
        long elapsed = Math.max(0, instanteRealNanos - origenRealNanos);
        double scaled = elapsed * factor;
        return scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) scaled;
    }
    public long milisegundosSimulados(long instanteInicioRealNanos, long instanteFinRealNanos) {
        long start = aTiempoSimuladoNanos(instanteInicioRealNanos);
        long end = aTiempoSimuladoNanos(instanteFinRealNanos);
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, end - start));
    }
}
