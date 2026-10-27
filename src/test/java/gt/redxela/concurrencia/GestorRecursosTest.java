package gt.redxela.concurrencia;

import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GestorRecursosTest {
    @Test void semaphoreRespetaCapacidadYReservaSeLiberaUnaSolaVez() throws Exception {
        var manager = new GestorRecursos();
        var needs = new EnumMap<TipoRecurso,Integer>(TipoRecurso.class); needs.put(TipoRecurso.ESCANER,1);
        var first = manager.adquirir("uno", needs);
        assertEquals(0, manager.snapshot().get(TipoRecurso.ESCANER).disponibles());
        var entered = new CountDownLatch(1); var acquired = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = executor.submit(() -> {
                entered.countDown();
                try (var ignored = manager.adquirir("dos", needs)) { acquired.countDown(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            first.close(); first.close();
            assertTrue(acquired.await(1, TimeUnit.SECONDS)); second.get(1, TimeUnit.SECONDS);
            assertEquals(1, manager.snapshot().get(TipoRecurso.ESCANER).disponibles());
        } finally { first.close(); executor.shutdownNow(); }
    }

    @Test void interrupcionMientrasEsperaLiberaLosRecursosYaAdquiridos() throws Exception {
        var manager = new GestorRecursos();
        var area = new EnumMap<TipoRecurso,Integer>(TipoRecurso.class); area.put(TipoRecurso.AREA_CARGA,2);
        var block = manager.adquirir("bloqueo", area);
        var needs = new EnumMap<TipoRecurso,Integer>(TipoRecurso.class);
        needs.put(TipoRecurso.EMPAQUE,1); needs.put(TipoRecurso.AREA_CARGA,2);
        var queued = new CountDownLatch(1); var result = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            queued.countDown();
            try { manager.adquirir("interrumpida", needs); }
            catch (InterruptedException expected) { result.countDown(); Thread.currentThread().interrupt(); }
        });
        thread.start();
        assertTrue(queued.await(1, TimeUnit.SECONDS));
        // Un límite corto solo acota la espera de sincronización; no simula exclusión mutua.
        thread.join(1000);
        assertEquals(Thread.State.WAITING, thread.getState());
        thread.interrupt(); assertTrue(result.await(1, TimeUnit.SECONDS)); thread.join(1000);
        block.close();
        assertEquals(TipoRecurso.EMPAQUE.capacidad(), manager.snapshot().get(TipoRecurso.EMPAQUE).disponibles());
        assertEquals(TipoRecurso.AREA_CARGA.capacidad(), manager.snapshot().get(TipoRecurso.AREA_CARGA).disponibles());
    }
}
