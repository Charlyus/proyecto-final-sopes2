package gt.redxela.almacenamiento;

import gt.redxela.modelo.Producto;
import java.util.*;

/**
 * Almacén determinista. Cada ubicación aloja un solo producto mientras tenga unidades;
 * al quedar vacía puede reutilizarse. Las mutaciones se invocan bajo el lock de Inventario.
 */
public final class Almacen {
    public record Ubicacion(String id, int capacidadVolumen, String productoId,
                            int volumenUtilizado) {
        public int volumenDisponible() { return capacidadVolumen - volumenUtilizado; }
    }
    public record ParteLote(String loteId, String productoId, String ubicacionId, int cantidad) { }
    public record PlanAlmacenamiento(List<ParteLote> partes) {
        public PlanAlmacenamiento { partes = List.copyOf(partes); }
    }
    private static final class Slot {
        final String id; final int capacity; String productId; int used;
        Slot(String id, int capacity) { this.id=id; this.capacity=capacity; }
    }
    private final List<Slot> slots;
    private final List<ParteLote> stock = new ArrayList<>();

    public Almacen(List<Integer> capacidadesVolumen) {
        Objects.requireNonNull(capacidadesVolumen);
        if (capacidadesVolumen.isEmpty()) throw new IllegalArgumentException("se requiere al menos una ubicación");
        slots = new ArrayList<>();
        for (int i=0; i<capacidadesVolumen.size(); i++) {
            Integer cap=Objects.requireNonNull(capacidadesVolumen.get(i));
            if (cap <= 0) throw new IllegalArgumentException("capacidad de ubicación debe ser positiva");
            slots.add(new Slot("UB-%03d".formatted(i+1), cap));
        }
    }
    public long capacidadTotal() { return slots.stream().mapToLong(s -> s.capacity).sum(); }
    public long volumenOcupado() { return slots.stream().mapToLong(s -> s.used).sum(); }

    /** Reserva capacidad entera de volumen; retorna null si no cabe ahora y no altera nada. */
    public PlanAlmacenamiento planificar(String lotId, Producto product, int quantity) {
        long volume = Math.multiplyExact((long) product.volumenPorUnidad(), quantity);
        if (volume > capacidadTotal()) throw new IllegalArgumentException("la recepción excede la capacidad total del almacén");
        int remaining = quantity;
        List<ParteLote> parts = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.productId != null && !slot.productId.equals(product.id())) continue;
            int availableVolume = slot.capacity - slot.used;
            int units = Math.min(remaining, availableVolume / product.volumenPorUnidad());
            if (units > 0) {
                parts.add(new ParteLote(lotId, product.id(), slot.id, units));
                remaining -= units;
            }
            if (remaining == 0) return new PlanAlmacenamiento(parts);
        }
        return null;
    }
    public void aplicar(PlanAlmacenamiento plan, Producto product) {
        // Prevalidar el plan completo antes de la primera mutación para conservar atomicidad.
        for (ParteLote part : plan.partes()) {
            Slot slot = slot(part.ubicacionId());
            if ((slot.productId != null && !slot.productId.equals(product.id()))
                    || slot.used + part.cantidad() * product.volumenPorUnidad() > slot.capacity)
                throw new IllegalStateException("el plan de almacenamiento quedó obsoleto");
        }
        for (ParteLote part : plan.partes()) {
            Slot slot = slot(part.ubicacionId()); slot.productId = product.id();
            slot.used += part.cantidad() * product.volumenPorUnidad(); stock.add(part);
        }
    }
    public void extraer(String productId, int quantity, Map<String,Integer> cantidadesPorUbicacion) {
        int remaining=quantity;
        for (int index=0; index<stock.size() && remaining>0;) {
            ParteLote part=stock.get(index);
            if (!part.productoId().equals(productId)) { index++; continue; }
            int take=Math.min(remaining, part.cantidad()); remaining-=take;
            cantidadesPorUbicacion.merge(part.ubicacionId(), take, Integer::sum);
            Slot slot=slot(part.ubicacionId()); slot.used -= take * volumenDe(productId);
            if (take == part.cantidad()) stock.remove(index);
            else { stock.set(index, new ParteLote(part.loteId(), part.productoId(), part.ubicacionId(), part.cantidad()-take)); index++; }
            if (slot.used == 0) slot.productId=null;
        }
        if (remaining != 0) throw new IllegalStateException("stock físico inconsistente");
    }
    private final Map<String,Integer> unitVolumes = new HashMap<>();
    public void registrarProducto(Producto product) { unitVolumes.put(product.id(), product.volumenPorUnidad()); }
    private int volumenDe(String productId) {
        Integer volume=unitVolumes.get(productId);
        if (volume==null) throw new IllegalStateException("producto desconocido en almacén");
        return volume;
    }
    public Map<String,Integer> stockPorProducto() {
        Map<String,Integer> result=new LinkedHashMap<>();
        for (ParteLote part : stock) result.merge(part.productoId(), part.cantidad(), Integer::sum);
        return Map.copyOf(result);
    }
    public List<ParteLote> lotes() { return List.copyOf(stock); }
    public List<Ubicacion> ubicaciones() {
        return slots.stream().map(s -> new Ubicacion(s.id,s.capacity,s.productId,s.used)).toList();
    }
    private Slot slot(String id) { return slots.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow(); }
}
