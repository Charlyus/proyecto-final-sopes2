package gt.redxela.modelo;

/** Niveles ordenados de mayor a menor prioridad base. */
public enum NivelServicio {
    EXPRES("Exprés", 5),
    PRIORITARIO("Prioritario", 4),
    ESTANDAR("Estándar", 3),
    PROGRAMADO("Programado", 2),
    ECONOMICO("Económico", 1);

    private final String etiqueta;
    private final int prioridad;

    NivelServicio(String etiqueta, int prioridad) {
        this.etiqueta = etiqueta;
        this.prioridad = prioridad;
    }

    public String etiqueta() { return etiqueta; }
    public int prioridad() { return prioridad; }

    /** Objetivo máximo de espera hasta el inicio efectivo; Exprés es cualitativo. */
    public Long objetivoEsperaMillis() {
        return switch (this) {
            case EXPRES -> null;
            case PRIORITARIO -> 10L * 60 * 1000;
            case ESTANDAR -> 30L * 60 * 1000;
            case PROGRAMADO -> 60L * 60 * 1000;
            case ECONOMICO -> 120L * 60 * 1000;
        };
    }

    public String objetivoEsperaTexto() {
        Long millis = objetivoEsperaMillis();
        return millis == null ? "Casi inmediato (cualitativo)" : "Máximo " + (millis / 60_000) + " min";
    }

    @Override public String toString() { return etiqueta; }
}
