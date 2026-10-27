package gt.redxela.inventario;

import gt.redxela.modelo.Producto;
import gt.redxela.modelo.TipoMercancia;
import java.util.*;

/** Catálogo fijo, con IDs canónicos iguales al nombre del tipo para compatibilidad. */
public final class CatalogoProductos {
    private static final Map<String, Producto> PRODUCTOS;
    static {
        EnumMap<TipoMercancia, Producto> p = new EnumMap<>(TipoMercancia.class);
        p.put(TipoMercancia.DOCUMENTOS, new Producto("DOCUMENTOS", "Documentos", TipoMercancia.DOCUMENTOS, 1));
        p.put(TipoMercancia.FRAGIL, new Producto("FRAGIL", "Paquete frágil", TipoMercancia.FRAGIL, 3));
        p.put(TipoMercancia.REFRIGERADO, new Producto("REFRIGERADO", "Mercadería refrigerada", TipoMercancia.REFRIGERADO, 4));
        p.put(TipoMercancia.VOLUMINOSO, new Producto("VOLUMINOSO", "Paquete voluminoso", TipoMercancia.VOLUMINOSO, 8));
        p.put(TipoMercancia.PELIGROSO, new Producto("PELIGROSO", "Mercadería peligrosa", TipoMercancia.PELIGROSO, 5));
        Map<String, Producto> byId = new LinkedHashMap<>(); p.values().forEach(x -> byId.put(x.id(), x));
        PRODUCTOS = Collections.unmodifiableMap(byId);
    }
    private CatalogoProductos() { }
    public static Collection<Producto> todos() { return PRODUCTOS.values(); }
    public static Producto porId(String id) {
        Producto p = PRODUCTOS.get(id);
        if (p == null) throw new IllegalArgumentException("producto no pertenece al catálogo: " + id);
        return p;
    }
}
