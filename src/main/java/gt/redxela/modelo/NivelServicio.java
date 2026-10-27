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

    @Override public String toString() { return etiqueta; }
}
