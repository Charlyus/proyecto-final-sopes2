package gt.redxela.planificacion;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanificadorPedidosTest {
    private final PlanificadorPedidos planner = new PlanificadorPedidos(100);
    private List<PlanificadorPedidos.Candidato<String>> candidates() {
        return List.of(new PlanificadorPedidos.Candidato<>("económico",1,10,0,0),
                new PlanificadorPedidos.Candidato<>("exprés",5,10,90,1),
                new PlanificadorPedidos.Candidato<>("estándar",3,2,90,2));
    }
    @Test void prioridadEstrictaYMenorTrabajo() {
        assertEquals("exprés", planner.seleccionar(candidates(), EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 100));
        assertEquals("estándar", planner.seleccionar(candidates(), EstrategiaPlanificacion.MENOR_CANTIDAD_PRIMERO, 100));
    }
    @Test void envejecimientoSeCalculaConRelojInyectadoYDesempateEstable() {
        assertEquals("exprés", planner.seleccionar(candidates(), EstrategiaPlanificacion.PRIORIDAD_CON_ENVEJECIMIENTO, 100));
        var aged = List.of(new PlanificadorPedidos.Candidato<>("económico",1,10,0,0),
                new PlanificadorPedidos.Candidato<>("exprés",5,10,590,1));
        assertEquals("económico", planner.seleccionar(aged, EstrategiaPlanificacion.PRIORIDAD_CON_ENVEJECIMIENTO, 600));
        var tied = List.of(new PlanificadorPedidos.Candidato<>("primero",2,1,0,3),
                new PlanificadorPedidos.Candidato<>("segundo",2,1,0,4));
        assertEquals("primero", planner.seleccionar(tied, EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 0));
    }

    @Test void pedidoDeMenorPrioridadAntiguoSuperaAUnoExprésReciénLlegado() {
        var orders=List.of(new PlanificadorPedidos.Candidato<>("económico antiguo",1,4,0,0),
                new PlanificadorPedidos.Candidato<>("exprés nuevo",5,1,590,1));
        assertEquals("exprés nuevo",planner.seleccionar(orders,EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,600));
        assertEquals("económico antiguo",planner.seleccionar(orders,EstrategiaPlanificacion.PRIORIDAD_CON_ENVEJECIMIENTO,600));
    }
}
