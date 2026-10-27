package gt.redxela.inventario;

public record EstadoStock(String productoId, String nombre, int fisico, int reservado, int disponible) { }
