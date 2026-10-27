# REDXela

Proyecto universitario de Sistemas Operativos 2. Java 17, Maven, JavaFX 17,
FXML, CSS, JUnit 5 y estado en memoria.

## Compilar, verificar y ejecutar

Desde la raíz del proyecto:

```bash
mvn clean verify
mvn javafx:run
```

Requisitos de ejecución: JDK 17 y una sesión de escritorio disponible. Maven
descarga OpenJFX 17.0.14 y sus bibliotecas nativas para la plataforma local; no
hace falta instalar JavaFX por separado. `mvn javafx:run` inicia
`gt.redxela.ui.RedXelaApp`.

Para construir el artefacto Maven sin iniciar la aplicación:

```bash
mvn package
```

El JAR Maven no incluye las bibliotecas nativas de JavaFX; para ejecutar la
interfaz usa `mvn javafx:run`.

## Demostración manual

1. Ejecuta la aplicación, selecciona una política en **Panel general** y pulsa
   **Iniciar motor**. La demostración también arranca el motor automáticamente.
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
5. En **Pedidos** se observan origen, cliente, servicio, unidades y estado.
   Al seleccionar una fila se muestran los productos, cantidades solicitadas,
   disponibles para ese pedido y faltantes. En **Cola de planificación** aparece
   el criterio ordenado por la política vigente en un instante común, con espera
   y prioridad efectiva.
   Es el criterio de selección, no una garantía del orden de inicio entre
   workers concurrentes. Los pedidos sin stock aparecen en espera de stock; los
   despachados muestran espera de recursos y procesamiento. Las demás pestañas
   muestran stock, ubicaciones, recepciones, recursos y estadísticas.

La interfaz JavaFX tiene un menú lateral para **Panel general**, **Pedidos**,
**Recepción**, **Almacén**, **Clientes** y **Conflictos**. Las páginas usan scroll vertical para
mantener accesibles los formularios y tablas en ventanas menores. El panel
general incluye indicadores, medias de espera y procesamiento y barras de
ocupación actual por recurso; Almacén muestra también su utilización acumulada.
Las tablas de pedidos presentan progreso y estados coloreados con texto.

## Generador automático

El panel **Pedidos automáticos** permite configurar intervalo entre llegadas
(milisegundos), rango inclusivo de unidades y semilla. La configuración se
captura al iniciar y se aplica al siguiente reinicio. El generador inicia el
motor si hace falta, usa un único hilo programado y acepta pedidos hasta pulsar
**Detener**. Al detenerlo, espera en segundo plano a que termine cualquier envío
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

La demostración configura cinco ubicaciones de 100 unidades de volumen: 500
unidades en total. Documentos ocupa 1 unidad de volumen por pieza, así que
500 documentos llenan todo el almacén. Antes de aceptar un pedido, el motor
suma líneas repetidas por producto y simula la asignación determinista sobre un
almacén vacío. Rechaza si el volumen excede el total o si la regla de producto
único por ubicación impide asignarlo, incluso cuando la suma de volúmenes sí
cabría. Esa validación común también aplica a pedidos automáticos. Un pedido
que sí cabe vacío pero carece temporalmente de stock se acepta en
`ESPERANDO_STOCK`; no se confunde con una imposibilidad estructural.

En el Panel general se muestran capacidad total, volumen ocupado y libre con
unidades. El detalle de pedido muestra disponibilidad respecto a las reservas
de esa orden y los faltantes; una reserva propia cuenta como disponible para
ella, y unidades ya consumidas aparecen satisfechas. Los encabezados de Estado
y Motivo usan espacio adaptable y tooltips para mostrar el contenido completo.

Una recepción imposible por volumen total se rechaza. Si la recepción cabe en el
almacén vacío pero no hay capacidad compatible en ese momento, queda pendiente
sin aplicación parcial. Al procesar un mensaje o liberar espacio, Inventario
reintenta pendientes en orden FIFO; omite temporalmente los que no caben para
que mensajes posteriores compatibles puedan avanzar. El motivo de una recepción
pendiente informa el volumen libre total, el volumen disponible en ubicaciones
compatibles y cuántas unidades enteras caben. Así, espacio libre total no se
presenta como espacio utilizable cuando pertenece a ubicaciones ocupadas por
otras mercancías o sus restos no admiten una unidad.

### Recepción, inventario y pedidos

Recepción ofrece solicitudes con `offer` a una `ArrayBlockingQueue` limitada.
La inserción nunca bloquea la interfaz JavaFX. El consumidor dedicado de Inventario procesa
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

## Demostración deliberada de deadlock

1. Inicia el motor y espera a que no haya pedidos en espera de recursos ni en
   procesamiento. Los pedidos aceptados que todavía esperan stock o despacho
   permanecen en sus colas.
2. Detén el generador y espera el estado **Detenido**. En **Conflictos**, pulsa
   **Provocar conflicto**. El sistema pausa coordinadamente los despachos y las
   nuevas recepciones; si hay trabajo operativo activo, informa el motivo y no
   inicia el escenario.
3. La Operación A (inspección) retiene el escáner y solicita un montacargas.
   La Operación B (preparación) retiene los tres permisos reales del recurso
   montacargas y solicita el escáner. Ambos usan los `Semaphore` y el registro
   propietario de `GestorRecursos`. Una barrera garantiza que cada operación
   tenga su primer recurso antes de pedir el segundo. Así, aunque existen tres
   montacargas, A no puede obtener ninguno mientras B los retiene todos.
4. El sistema declara deadlock solo cuando el grafo real de espera contiene
   `Operación A → Operación B → Operación A`. Un recurso ocupado sin ciclo no
   se considera deadlock. Las tarjetas muestran retenciones, solicitud y
   propietario esperado.
5. Pulsa **Continuar A** o **Continuar B**. La operación desplazada se
   interrumpe y libera sus propios recursos mediante `finally`. El coordinador
   confirma que terminó de liberar antes de dejar avanzar a la elegida. Luego
   la desplazada queda visible en espera y reintenta usando el orden global
   seguro. **Cancelar demostración** interrumpe ambas operaciones y espera la
   limpieza antes de restaurar los componentes normales.

El ejercicio ilustra las cuatro condiciones de Coffman:

- **Exclusión mutua:** el escáner tiene capacidad uno; B ocupa los tres
  permisos de montacargas.
- **Retener y esperar:** A conserva el escáner mientras pide montacargas; B
  conserva montacargas mientras pide el escáner.
- **No expropiación:** el administrador no retira permisos de forma arbitraria.
  La resolución interrumpe una operación y esta libera lo propio al salir.
- **Espera circular:** A espera recursos de B y B espera el escáner de A.

El escenario no produce efectos de inventario: no reserva, consume, mueve ni
repone stock. Las solicitudes de recepción ya aceptadas se conservan; durante
la pausa, nuevas recepciones se rechazan explícitamente. La demostración solo
comienza con recursos libres, sin pedidos despachados y con el generador
detenido.

## Comparación controlada de planificación

Desde el menú **Comparación**, elige **Competencia media (15 pedidos)** o
**Competencia alta (20 pedidos)**, una semilla y un límite por política; luego
pulsa **Ejecutar comparación**. La tabla muestra el estado de cada ejecución,
los cinco conteos de pedidos, las métricas y un gráfico de espera media. Al
seleccionar una política se ven sus esperas por nivel de servicio y la
utilización de cada recurso. **Cancelar** detiene nuevas llegadas y cierra la
ejecución actual antes de marcarla cancelada. Un timeout se muestra como
**Límite agotado**: conserva los conteos observados, pero no presenta tiempos
parciales como métricas completas.

Cada política usa una instancia nueva de Motor, GestorRecursos, Inventario,
Recepción y Almacén, y se ejecuta secuencialmente. La preparación del stock se
completa y verifica antes del inicio medido. Las tres instancias reciben los
mismos pedidos programados, IDs lógicos, servicios, cantidades y llegadas
relativas; también comparten trabajadores, recursos y duración por unidad. El
motor interactivo del usuario no se modifica.

La configuración inicial es:

- Semilla predeterminada: `42` (editable).
- 15 o 20 pedidos, con los cinco servicios distribuidos de forma balanceada y
  barajada por la semilla; 1–3 unidades por pedido y los cinco tipos de
  mercancía. Los IDs lógicos `CMP-001`…`CMP-020` son estables.
- Llegadas cada `5 ms`, contados desde el comienzo de cada ejecución; `2`
  trabajadores; `20 ms reales` de trabajo por unidad; envejecimiento cada
  `80 ms reales`; límite predeterminado `15 000 ms` por política (editable de
  100 a 120 000 ms).
- Capacidades comunes: 6 empaques, 2 áreas de carga, 10 encargados, 3
  montacargas, 4 estaciones de calidad y 1 escáner.
- Cinco ubicaciones de `100` unidades de volumen. El stock inicial es la suma
  exacta de las necesidades del escenario; se almacena explícitamente antes
  de medir. Por producto no supera el espacio de una ubicación, y el escenario
  valida la capacidad con la misma regla de almacén que usa la aplicación.
  No hay falta de stock ni reposición silenciosa.

Las unidades de `TiempoSimulado` coinciden aquí con tiempo real de espera del
hilo: no hay aceleración de reloj ni conversión a minutos simulados. Las
mediciones usan `System.nanoTime()` y se muestran en milisegundos reales,
pedidos por segundo o porcentaje:

- Espera por pedido: `inicio efectivo − llegada`; media y máximo solo usan
  pedidos con inicio registrado. La tabla secundaria promedia la misma medida
  por nivel de servicio.
- Procesamiento: `finalización − inicio efectivo`; la media usa pedidos que
  terminaron.
- Llegada a fin: `finalización − llegada`; media de pedidos completados.
- Duración total: inicio de la reproducción hasta la última finalización.
- Rendimiento: pedidos completados / duración total en segundos.
- Utilización del recurso: nanosegundos de permisos ocupados / (capacidad ×
  ventana observada). La ventana empieza justo antes de reproducir las
  llegadas y termina al comprobar recursos liberados.

Si una medida no tiene muestras, se presenta como `—`, no como cero. Exprés
conserva “casi inmediato” como objetivo cualitativo; esta comparación no fija
un umbral numérico. La prioridad estricta privilegia niveles altos, el
envejecimiento puede mejorar la atención de los pedidos que esperan y menor
trabajo primero favorece tareas cortas. El escenario permite observar esos
compromisos, pero no demuestra que una política sea siempre mejor. La misma
semilla conserva las entradas; la planificación del sistema operativo y la
contención pueden variar los tiempos medidos entre repeticiones.

## Limitaciones actuales

Todo el estado desaparece al cerrar el proceso. No se implementan persistencia,
generación automática de stock ni manuales finales. El catálogo es
fijo y solo se pueden seleccionar sus productos. Los pedidos automáticos
pueden crecer sin límite de negocio, pero están condicionados por el tiempo y
la memoria disponibles en la máquina.

El error `Double cannot be cast to Long` ocurre al hacer un cast directo de un
valor numérico de control cuyo tipo real es `Double` al wrapper `Long` (no son
tipos compatibles por cast). Los nuevos campos aceptan texto y convierten con
`Integer.parseInt` o `Long.parseLong`, validando que cantidades sean enteras y
estén dentro del rango antes de enviar comandos.
