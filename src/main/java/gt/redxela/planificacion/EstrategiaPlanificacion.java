package gt.redxela.planificacion;

public enum EstrategiaPlanificacion {
    PRIORIDAD_ESTRICTA,
    PRIORIDAD_CON_ENVEJECIMIENTO,
    MENOR_CANTIDAD_PRIMERO;

    @Override public String toString() {
        return switch (this) {
            case PRIORIDAD_ESTRICTA -> "Prioridad estricta";
            case PRIORIDAD_CON_ENVEJECIMIENTO -> "Prioridad con envejecimiento";
            case MENOR_CANTIDAD_PRIMERO -> "Menor trabajo primero";
        };
    }
}
