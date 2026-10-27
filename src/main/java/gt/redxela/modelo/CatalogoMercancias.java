package gt.redxela.modelo;

import java.util.Map;

/** Catálogo preliminar; cada tipo declara una combinación de recursos diferenciada. */
public final class CatalogoMercancias {
    private static final Map<TipoMercancia, RequisitoRecursos> REQUISITOS = Map.of(
            TipoMercancia.DOCUMENTOS, new RequisitoRecursos(1, 0, 1, 0, 1, 1),
            TipoMercancia.FRAGIL, new RequisitoRecursos(1, 0, 2, 0, 1, 1),
            TipoMercancia.REFRIGERADO, new RequisitoRecursos(1, 1, 2, 0, 1, 1),
            TipoMercancia.VOLUMINOSO, new RequisitoRecursos(1, 1, 2, 1, 1, 1),
            TipoMercancia.PELIGROSO, new RequisitoRecursos(1, 1, 1, 0, 2, 1));

    private CatalogoMercancias() { }
    public static Map<TipoMercancia, RequisitoRecursos> todos() { return REQUISITOS; }
    public static RequisitoRecursos requisitosDe(TipoMercancia tipo) {
        if (tipo == null) throw new NullPointerException("tipo es obligatorio");
        return REQUISITOS.get(tipo);
    }
}
