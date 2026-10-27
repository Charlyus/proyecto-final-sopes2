package gt.redxela.concurrencia;

public enum TipoRecurso {
    EMPAQUE("Estaciones de empaque", 6), AREA_CARGA("Áreas de carga", 2),
    ENCARGADO("Encargados", 10), MONTACARGAS("Montacargas", 3),
    CALIDAD("Estaciones de calidad", 4), ESCANER("Escáner compartido", 1);
    private final String etiqueta;
    private final int capacidad;
    TipoRecurso(String etiqueta, int capacidad) { this.etiqueta = etiqueta; this.capacidad = capacidad; }
    public String etiqueta() { return etiqueta; }
    public int capacidad() { return capacidad; }
}
