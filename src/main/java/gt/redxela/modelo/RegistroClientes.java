package gt.redxela.modelo;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Registro seguro para cuentas persistidas solo durante la ejecución. */
public final class RegistroClientes {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<UUID, Cliente> porId = new LinkedHashMap<>();
    private final Map<String, UUID> porCorreo = new HashMap<>();

    public Cliente registrar(String nombre, String correo, NivelServicio servicio) {
        Cliente cliente = new Cliente(nombre, correo, servicio);
        lock.writeLock().lock();
        try {
            if (porCorreo.containsKey(cliente.correoNormalizado()))
                throw new IllegalArgumentException("ya existe una cuenta con ese correo");
            if (porId.containsKey(cliente.id())) throw new IllegalStateException("colisión de identificador de cliente");
            porId.put(cliente.id(), cliente); porCorreo.put(cliente.correoNormalizado(), cliente.id());
            return cliente;
        } finally { lock.writeLock().unlock(); }
    }
    public Cliente actualizarServicio(UUID id, NivelServicio servicio) {
        Objects.requireNonNull(servicio);
        lock.writeLock().lock();
        try {
            Cliente previo = Optional.ofNullable(porId.get(id)).orElseThrow(() -> new NoSuchElementException("cliente inexistente"));
            Cliente actualizado = new Cliente(id, previo.nombre(), previo.correo(), servicio);
            porId.put(id, actualizado); return actualizado;
        } finally { lock.writeLock().unlock(); }
    }
    public Optional<Cliente> buscar(UUID id) {
        lock.readLock().lock(); try { return Optional.ofNullable(porId.get(id)); }
        finally { lock.readLock().unlock(); }
    }
    public Optional<Cliente> buscarCorreo(String correo) {
        String key = normalizarCorreo(correo);
        lock.readLock().lock(); try { UUID id = porCorreo.get(key); return Optional.ofNullable(id == null ? null : porId.get(id)); }
        finally { lock.readLock().unlock(); }
    }
    public List<Cliente> snapshot() {
        lock.readLock().lock(); try { return List.copyOf(porId.values()); }
        finally { lock.readLock().unlock(); }
    }
    public static String normalizarCorreo(String correo) {
        Objects.requireNonNull(correo, "correo es obligatorio");
        return correo.trim().toLowerCase(Locale.ROOT);
    }
}
