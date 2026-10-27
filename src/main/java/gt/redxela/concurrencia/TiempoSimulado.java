package gt.redxela.concurrencia;

/** El trabajo dura unidades * milisegundosPorUnidad; System.nanoTime mide duraciones monotónicamente. */
public record TiempoSimulado(long milisegundosPorUnidad) {
    public TiempoSimulado {
        if (milisegundosPorUnidad <= 0) throw new IllegalArgumentException("el tiempo por unidad debe ser positivo");
    }
    public long duracionMillis(int unidades) {
        if (unidades <= 0) throw new IllegalArgumentException("unidades debe ser positivo");
        return Math.multiplyExact((long) unidades, milisegundosPorUnidad);
    }
}
