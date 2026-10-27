package gt.redxela.simulacion;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.inventario.CatalogoProductos;
import gt.redxela.modelo.NivelServicio;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Entradas comunes e inmutables para comparar las tres políticas. */
public record EscenarioComparacion(Perfil perfil, long semilla, List<PedidoProgramado> pedidos,
                                   Map<String,Integer> stockInicial, List<Integer> capacidadesUbicaciones,
                                   int trabajadores, long milisegundosPorUnidad,
                                   long intervaloLlegadasMillis, long intervaloEnvejecimientoMillis,
                                   long limiteEjecucionMillis) {
    public record Perfil(String nombre, int cantidadPedidos) {
        public Perfil {
            Objects.requireNonNull(nombre);
            if(cantidadPedidos<5 || cantidadPedidos>20 || cantidadPedidos%5!=0)
                throw new IllegalArgumentException("el escenario debe tener entre 5 y 20 pedidos, en grupos de cinco");
        }
        @Override public String toString() { return nombre; }
    }
    public record PedidoProgramado(String idLogico, long llegadaRelativaMillis,
                                   String productoId, int cantidad, NivelServicio servicio) {
        public PedidoProgramado {
            Objects.requireNonNull(idLogico); Objects.requireNonNull(productoId); Objects.requireNonNull(servicio);
            if(idLogico.isBlank()||llegadaRelativaMillis<0||cantidad<=0) throw new IllegalArgumentException("pedido programado inválido");
            CatalogoProductos.porId(productoId);
        }
        public UUID pedidoUuid() { return UUID.nameUUIDFromBytes(("redxela-comparacion:"+idLogico).getBytes(StandardCharsets.UTF_8)); }
    }

    public static final List<Perfil> PERFILES=List.of(new Perfil("Competencia media · 15 pedidos",15),
            new Perfil("Competencia alta · 20 pedidos",20));
    public static final List<Integer> UBICACIONES=List.of(100,100,100,100,100);
    public static final int TRABAJADORES=2;
    public static final long MILISEGUNDOS_POR_UNIDAD=20;
    public static final long INTERVALO_LLEGADAS_MILLIS=5;
    public static final long INTERVALO_ENVEJECIMIENTO_MILLIS=80;
    public static final long LIMITE_POR_POLITICA_MILLIS=15_000;

    public EscenarioComparacion {
        Objects.requireNonNull(perfil); pedidos=List.copyOf(pedidos); stockInicial=Map.copyOf(stockInicial);
        capacidadesUbicaciones=List.copyOf(capacidadesUbicaciones);
        if(pedidos.size()!=perfil.cantidadPedidos()) throw new IllegalArgumentException("cantidad de pedidos no coincide con el perfil");
        if(trabajadores<=0||milisegundosPorUnidad<=0||intervaloLlegadasMillis<=0
                ||intervaloEnvejecimientoMillis<=0||limiteEjecucionMillis<=0) throw new IllegalArgumentException("configuración de ejecución inválida");
        Almacen warehouse=new Almacen(capacidadesUbicaciones);
        Map<String,Integer> computed=new TreeMap<>();
        for(PedidoProgramado order:pedidos) computed.merge(order.productoId(),order.cantidad(),Math::addExact);
        if(!computed.equals(new TreeMap<>(stockInicial))) throw new IllegalArgumentException("el stock inicial debe cubrir exactamente el escenario");
        Map<String,gt.redxela.modelo.Producto> products=new HashMap<>();
        CatalogoProductos.todos().forEach(p->products.put(p.id(),p));
        warehouse.validarCabeEnAlmacenVacio(products,computed);
    }

    public static EscenarioComparacion generar(Perfil perfil,long semilla) {
        Objects.requireNonNull(perfil);
        Random random=new Random(semilla);
        List<NivelServicio> services=new ArrayList<>();
        for(int i=0;i<perfil.cantidadPedidos()/NivelServicio.values().length;i++) services.addAll(List.of(NivelServicio.values()));
        Collections.shuffle(services,random);
        List<String> products=new ArrayList<>();
        CatalogoProductos.todos().forEach(p->products.add(p.id()));
        Collections.shuffle(products,random);
        List<PedidoProgramado> orders=new ArrayList<>(); Map<String,Integer> stock=new TreeMap<>();
        for(int i=0;i<perfil.cantidadPedidos();i++) {
            String product=products.get(i%products.size());
            int quantity=1+random.nextInt(3);
            orders.add(new PedidoProgramado("CMP-%03d".formatted(i+1),i*INTERVALO_LLEGADAS_MILLIS,
                    product,quantity,services.get(i)));
            stock.merge(product,quantity,Math::addExact);
        }
        return new EscenarioComparacion(perfil,semilla,orders,stock,UBICACIONES,TRABAJADORES,
                MILISEGUNDOS_POR_UNIDAD,INTERVALO_LLEGADAS_MILLIS,INTERVALO_ENVEJECIMIENTO_MILLIS,
                LIMITE_POR_POLITICA_MILLIS);
    }

    public long volumenStockInicial() {
        return stockInicial.entrySet().stream().mapToLong(e ->
                (long)CatalogoProductos.porId(e.getKey()).volumenPorUnidad()*e.getValue()).sum();
    }
    public long capacidadAlmacen() { return capacidadesUbicaciones.stream().mapToLong(Integer::longValue).sum(); }
    public String fingerprint() {
        return semilla+"|"+perfil.cantidadPedidos()+"|"+pedidos+"|"+new TreeMap<>(stockInicial)+"|"+
                capacidadesUbicaciones+"|"+trabajadores+"|"+milisegundosPorUnidad+"|"+
                intervaloLlegadasMillis+"|"+intervaloEnvejecimientoMillis+"|"+limiteEjecucionMillis;
    }
    public EscenarioComparacion conLimiteEjecucion(long millis) {
        return new EscenarioComparacion(perfil,semilla,pedidos,stockInicial,capacidadesUbicaciones,trabajadores,
                milisegundosPorUnidad,intervaloLlegadasMillis,intervaloEnvejecimientoMillis,millis);
    }
}
