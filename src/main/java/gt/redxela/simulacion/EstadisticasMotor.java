package gt.redxela.simulacion;

import gt.redxela.concurrencia.GestorRecursos;
import gt.redxela.concurrencia.TipoRecurso;
import java.util.Map;

public record EstadisticasMotor(int pendientes, int esperandoStock, int esperandoRecursos, int procesandose,
                                int completados, Double esperaPromedioMillis,
                                Double procesamientoPromedioMillis,
                                Map<TipoRecurso, GestorRecursos.EstadoRecurso> recursos,
                                Map<TipoRecurso, Double> utilizacionAcumulada) {
    public EstadisticasMotor {
        recursos = Map.copyOf(recursos);
        utilizacionAcumulada = Map.copyOf(utilizacionAcumulada);
    }
}
