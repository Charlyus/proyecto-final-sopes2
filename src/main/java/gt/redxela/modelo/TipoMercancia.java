package gt.redxela.modelo;

public enum TipoMercancia {
    DOCUMENTOS("Documentos", "Paquetería ligera de papel y sobres, con manipulación y empaque básicos."),
    FRAGIL("Frágil", "Artículos delicados que requieren manipulación cuidadosa y control de calidad."),
    REFRIGERADO("Refrigerado", "Productos que requieren coordinación de carga y conservación de temperatura."),
    VOLUMINOSO("Voluminoso", "Bultos grandes que requieren coordinación de carga y montacargas."),
    PELIGROSO("Peligroso", "Mercancía controlada que requiere manejo especializado y controles de calidad adicionales.");

    private final String etiqueta;
    private final String descripcion;
    TipoMercancia(String etiqueta, String descripcion) { this.etiqueta = etiqueta; this.descripcion = descripcion; }
    public String etiqueta() { return etiqueta; }
    public String descripcion() { return descripcion; }
    @Override public String toString() { return etiqueta; }
}
