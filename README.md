# REDXela

Proyecto universitario de Sistemas Operativos 2. Java 17, Maven, Swing, JUnit 5
y estado en memoria.

## Compilar, verificar y ejecutar

Desde la raíz del proyecto:

```bash
mvn clean verify
mvn exec:java -Dexec.mainClass=gt.redxela.ui.RedXelaApp
```

También se puede crear y ejecutar el JAR:

```bash
mvn package
java -jar target/redxela-1.0-SNAPSHOT.jar
```

## Demostración manual

1. Ejecuta la aplicación y selecciona una política de planificación.
2. Pulsa **Iniciar motor** para procesar recepciones y pedidos manuales, o
   **Cargar demostración con stock inicial** para encolar existencias explícitas
   (6 Documentos, 2 Paquetes frágiles y 3 Paquetes voluminosos) y tres pedidos.
3. En **Registrar recepción**, selecciona un producto del catálogo fijo y la
   cantidad. La cola de recepción admite 12 solicitudes. Si está llena, la UI
   informa el rechazo sin presentarlo como aceptado. Las recepciones que excedan
   la capacidad total también se rechazan inmediatamente.
4. Para un pedido, agrega una o varias líneas, elige el servicio y pulsa
   **Registrar pedido**. El estado muestra por separado espera de stock y de
   recursos. Las pestañas presentan pedidos, stock físico/reservado/disponible,
   ubicaciones, recepciones, recursos y estadísticas.

## Decisiones de esta etapa

### Catálogo y almacenamiento

El catálogo fijo tiene un producto para cada tipo de mercancía. Cada producto
tiene ID, nombre, tipo y volumen entero por unidad. Las capacidades del almacén
se expresan también en unidades enteras de volumen.

La asignación recorre las ubicaciones por ID en orden ascendente. Un lote se
divide entre las primeras ubicaciones compatibles que tengan capacidad; por eso
puede ocupar ubicaciones no contiguas. Una ubicación admite un solo producto
mientras conserve unidades, pero puede almacenar muchas unidades de ese
producto hasta llenar su volumen. Cuando queda vacía, puede reutilizarse.

Una recepción imposible por volumen total se rechaza. Si la recepción cabe en el
almacén vacío pero no hay capacidad compatible en ese momento, queda pendiente
sin aplicación parcial. Al procesar un mensaje o liberar espacio, Inventario
reintenta pendientes en orden FIFO; omite temporalmente los que no caben para
que mensajes posteriores compatibles puedan avanzar.

### Recepción, inventario y pedidos

Recepción ofrece solicitudes con `offer` a una `ArrayBlockingQueue` limitada.
La inserción nunca bloquea el EDT. El consumidor dedicado de Inventario procesa
los mensajes FIFO y obtiene el semáforo compartido del escáner antes de aplicar
una recepción. IDs únicos y registro de procesados evitan aplicar un mensaje dos
veces. Al cerrar, se detiene la aceptación y el consumer; mensajes todavía en
cola permanecen sin marcar como procesados.

Inventario deriva el stock físico de los lotes realmente ubicados. Las reservas
se comprueban y aplican de forma atómica para todas las líneas; cantidades del
mismo producto se suman por ID. Stock disponible es físico menos reservado. La
política activa ordena qué pedidos pendientes prueban primero la reserva cuando
llega mercadería; si una solicitud no tiene stock, queda en `ESPERANDO_STOCK` y
no ocupa worker ni recursos operativos.

El pedido consume físicamente su reserva solo después de adquirir todos sus
recursos y justo antes de empezar preparación. En ese punto disminuyen las
existencias por lote y se libera la ubicación correspondiente. Si se cancela
antes de consumir, la reserva vuelve a disponible. Una interrupción después de
consumir cancela el trabajo, pero no repone la mercadería, porque ya pudo entrar
en preparación.

La integración compone Inventario y Almacén con un único lock de inventario para
que cada snapshot concuerde con los lotes. El motor no conserva el lock de
despacho mientras espera recursos ni durante el tiempo simulado. El mismo
`GestorRecursos` administra recursos de preparación y el escáner usado por
recepción.

### Planificación y tiempo

La prioridad estricta usa Exprés (5), Prioritario (4), Estándar (3), Programado
(2) y Económico (1), con orden de llegada para empates. El envejecimiento se
recalcula al seleccionar y usa
`prioridad_base + floor(espera_nanos / intervalo_nanos)`. Por ello un pedido
Económico con suficiente antigüedad sí puede superar a un Exprés recién llegado;
una prueba lo compara con el mismo reloj simulado. Menor trabajo primero ordena
por la suma de unidades de sus líneas y luego por llegada.

El trabajo simulado dura `unidades_totales × milisegundos_por_unidad` (250 ms por
unidad en la demostración) y usa `System.nanoTime()` para medir duración.

## Limitaciones actuales

Todo el estado desaparece al cerrar el proceso. No se implementan todavía el
generador continuo, cuentas completas, escenario deliberado de deadlock ni
comparación de ejecuciones entre políticas. El catálogo es fijo; el formulario
solo permite seleccionar sus productos. La ventana presenta pedidos de un
cliente de demostración.
