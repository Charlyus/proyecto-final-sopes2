package gt.redxela.modelo;

/** Cantidades de recursos requeridas por unidad de mercancía. */
public record RequisitoRecursos(int empaques, int areasCarga, int encargados,
                                int montacargas, int estacionesCalidad, int escaneres) {
    public RequisitoRecursos {
        if (empaques < 0 || areasCarga < 0 || encargados < 0 || montacargas < 0
                || estacionesCalidad < 0 || escaneres < 0) {
            throw new IllegalArgumentException("los requisitos de recursos no pueden ser negativos");
        }
    }
}
