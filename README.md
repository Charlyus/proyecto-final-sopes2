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

1. Ejecuta la aplicación y selecciona una política de planificación antes de
   iniciar el motor.
2. Registra clientes con nombre, correo y servicio por defecto. El correo se
   recorta y normaliza a minúsculas; no se aceptan duplicados ignorando
   mayúsculas y espacios exteriores. El selector de pedidos permite escoger
   una cuenta y modificar el servicio para ese pedido. El servicio contratado
   se captura en el pedido y no cambia al actualizar la cuenta.
3. En **Registrar recepción**, elige un producto existente y una cantidad. La
   cola acepta hasta 12 solicitudes; rechazos por saturación son explícitos.
   Una recepción aplicada actualiza el inventario. El generador nunca repone
   mercancía por sí mismo.
4. Para un pedido manual agrega una o más líneas y pulsa **Registrar pedido**.
   Se conserva la demostración anterior en **Cargar demostración con stock
   inicial**, que genera tres pedidos con origen `DEMOSTRACION`.
5. En **Pedidos** se observan origen, cliente, servicio, unidades y estado. En
   **Cola de planificación** aparece la cola lista para despacho ordenada por
   la política vigente en un instante común, con espera y prioridad efectiva.
   Es el criterio de selección, no una garantía del orden de inicio entre
   workers concurrentes. Los pedidos sin stock aparecen en espera de stock; los
   despachados muestran espera de recursos y procesamiento. Las demás pestañas
   muestran stock, ubicaciones, recepciones, recursos y estadísticas.

## Generador automático

El panel **Pedidos automáticos** permite configurar intervalo entre llegadas
(milisegundos), rango inclusivo de unidades y semilla. La configuración se
captura al iniciar y se aplica al siguiente reinicio. El generador inicia el
motor si hace falta, usa un único hilo programado y acepta pedidos hasta pulsar
**Detener**. Al detenerlo, espera fuera del EDT a que termine cualquier envío
en curso; los pedidos ya aceptados siguen en el motor. Los fallos de envío se
muestran como error y la tarea continúa intentando en la próxima llegada.

Los niveles siguen distribución cíclica uniforme: Exprés, Prioritario,
Estándar, Programado, Económico, repetidamente (20% cada uno en secuencias
largas). La semilla determina niveles, cuentas sintéticas, productos y
cantidades; los UUID y las marcas de tiempo son únicos por ejecución. Clientes
sintéticos se identifican con `[Sintético]`. Para una misma semilla, rango e
intervalo se repite la secuencia de contenido del pedido; el orden real de
procesamiento puede variar por concurrencia.

Para demostrar acumulación, inicia el motor con el botón de demostración o
registra stock suficiente de productos usados, y configura una llegada rápida
(por ejemplo 100 ms) con pedidos de varias unidades. El trabajo simulado dura
250 ms por unidad y hay tres workers: si la llegada supera esa tasa de
procesamiento, crecerán las colas de stock o de recursos. Sin stock, los
pedidos automáticos quedan en espera de stock y no consumen workers; para
demostrar competencia por recursos, recibe existencias primero y observa
**Recursos** y los estados de pedidos. El generador no filtra productos por
disponibilidad ni descarta pedidos por falta de stock.

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

El registro de clientes usa un `ReentrantReadWriteLock`; cada cuenta tiene ID
UUID, nombre, correo y servicio por defecto. Una cuenta no contiene contraseña
ni autenticación. Pedidos y cuentas, igual que inventario y estadísticas, solo
existen en memoria y se pierden al cerrar la aplicación. Las estadísticas
reflejan datos de ejecución. El generador puede funcionar continuamente
mientras la aplicación siga activa y la máquina tenga recursos suficientes;
no hay un límite prefijado de cantidad, pero el historial en memoria aumenta
con cada pedido.

## Limitaciones actuales

Todo el estado desaparece al cerrar el proceso. No se implementan persistencia,
generación automática de stock, deadlock deliberado y resolución manual,
comparación de ejecuciones entre políticas ni manuales finales. El catálogo es
fijo y solo se pueden seleccionar sus productos. Los pedidos automáticos
pueden crecer sin límite de negocio, pero están condicionados por el tiempo y
la memoria disponibles en la máquina.
