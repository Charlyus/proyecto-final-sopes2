package gt.redxela.modelo;

public enum TipoMercancia {
    DOCUMENTOS("Documentos"),
    FRAGIL("Frágil"),
    REFRIGERADO("Refrigerado"),
    VOLUMINOSO("Voluminoso"),
    PELIGROSO("Peligroso");

    private final String etiqueta;
    TipoMercancia(String etiqueta) { this.etiqueta = etiqueta; }
    public String etiqueta() { return etiqueta; }
    @Override public String toString() { return etiqueta; }
}
