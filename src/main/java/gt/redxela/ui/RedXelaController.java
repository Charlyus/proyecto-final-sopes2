package gt.redxela.ui;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.*;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import gt.redxela.simulacion.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Controlador de composición visual; la lógica de stock, despacho y planificación sigue en sus servicios. */
public final class RedXelaController {
    private record ResourceRow(TipoRecurso tipo, GestorRecursos.EstadoRecurso state, double utilization) { }
    private record OrderLineDetail(String productName, int requested, int available, int missing) { }
    @FXML private BorderPane root;
    @FXML private VBox navigation;
    @FXML private StackPane content;
    @FXML private Label footerStatus;

    private final Map<String, Label> metrics = new HashMap<>();
    private final Map<String, Node> pages = new LinkedHashMap<>();
    private final ObservableList<LineaPedido> draftLines = FXCollections.observableArrayList();
    private final RegistroClientes clients = new RegistroClientes();
    private final GestorRecursos resources = new GestorRecursos();
    private final ArrayBlockingQueue<SolicitudRecepcion> receiptQueue = new ArrayBlockingQueue<>(12);
    private final Almacen warehouse = new Almacen(List.of(100, 100, 100, 100, 100));
    private final Recepcion reception = new Recepcion(receiptQueue, warehouse.capacidadTotal());
    private final Inventario inventory = new Inventario(receiptQueue, reception, warehouse, resources);
    private final MotorSimulacion motor = new MotorSimulacion(3, new TiempoSimulado(250),
            EstrategiaPlanificacion.PRIORIDAD_ESTRICTA, 10_000, resources);
    private final GeneradorPedidos generator;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "redxela-ui-tareas"); t.setDaemon(false); return t;
    });
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicBoolean motorStarted = new AtomicBoolean();
    private final ComboBox<EstrategiaPlanificacion> policy = new ComboBox<>();
    private final ComboBox<EstrategiaPlanificacion> orderPolicy = new ComboBox<>();
    private final ComboBox<Producto> receiptProduct = new ComboBox<>();
    private final TextField receiptQuantity = new TextField("1");
    private final ComboBox<Producto> orderProduct = new ComboBox<>();
    private final TextField orderQuantity = new TextField("1");
    private final ComboBox<Cliente> orderClient = new ComboBox<>();
    private final ComboBox<NivelServicio> orderService = new ComboBox<>();
    private final ComboBox<NivelServicio> accountService = new ComboBox<>();
    private final ComboBox<NivelServicio> selectedAccountService = new ComboBox<>();
    private final TextField accountName = new TextField();
    private final TextField accountEmail = new TextField();
    private final TextField interval = new TextField("500");
    private final TextField unitsMin = new TextField("1");
    private final TextField unitsMax = new TextField("5");
    private final TextField seed = new TextField("42");
    private final Label generatorStatus = new Label("Generador detenido");
    private final Label generatorCount = new Label("0 pedidos automáticos");
    private final Label lastGeneratorError = new Label();
    private TableView<EstadoPedidoMotor> orderTable;
    private TableView<OrderLineDetail> orderDetailTable;
    private Label orderDetailTitle;
    private TableView<EntradaPlanificacion> queueTable;
    private TableView<LineaPedido> draftTable;
    private TableView<EstadoRecepcionVista> receiptTable;
    private TableView<EstadoStock> stockTable;
    private TableView<Almacen.Ubicacion> locationTable;
    private TableView<ResourceRow> resourceTable;
    private TableView<Cliente> clientTable;
    private VBox resourceOccupancy;
    private Label runtimeStatistics;
    private Label warehouseCapacitySummary;
    private Label motorStatusIndicator;
    private Button motorStartButton;
    private Timeline refreshTimeline;
    private Stage stage;
    private boolean demoLoaded;
    private boolean demoLoading;
    private boolean allowClose;
    private volatile EstrategiaPlanificacion selectedPolicy = EstrategiaPlanificacion.PRIORIDAD_ESTRICTA;

    public RedXelaController() {
        motor.conectarInventario(inventory);
        clients.registrar("Cliente demostración", "demo@redxela.local", NivelServicio.ESTANDAR);
        generator = new GeneradorPedidos(motor, clients, message -> Platform.runLater(() -> {
            lastGeneratorError.setText(message);
            lastGeneratorError.getStyleClass().setAll("status-bad");
            setFooter(message);
        }));
    }

    @FXML private void initialize() {
        policy.setItems(FXCollections.observableArrayList(EstrategiaPlanificacion.values()));
        policy.setValue(EstrategiaPlanificacion.PRIORIDAD_ESTRICTA);
        orderPolicy.setItems(FXCollections.observableArrayList(EstrategiaPlanificacion.values()));
        orderPolicy.setValue(EstrategiaPlanificacion.PRIORIDAD_ESTRICTA);
        receiptProduct.setItems(FXCollections.observableArrayList(CatalogoProductos.todos()));
        orderProduct.setItems(FXCollections.observableArrayList(CatalogoProductos.todos()));
        receiptProduct.getSelectionModel().selectFirst(); orderProduct.getSelectionModel().selectFirst();
        orderService.setItems(FXCollections.observableArrayList(NivelServicio.values())); orderService.setValue(NivelServicio.ESTANDAR);
        accountService.setItems(FXCollections.observableArrayList(NivelServicio.values())); accountService.setValue(NivelServicio.ESTANDAR);
        selectedAccountService.setItems(FXCollections.observableArrayList(NivelServicio.values())); selectedAccountService.setValue(NivelServicio.ESTANDAR);
        orderClient.setMaxWidth(Double.MAX_VALUE);
        buildNavigation();
        pages.put("Panel general", dashboardPage()); pages.put("Pedidos", ordersPage());
        pages.put("Recepción", receptionPage()); pages.put("Almacén", warehousePage()); pages.put("Clientes", clientsPage());
        orderClient.valueProperty().addListener((obs, old, value) -> { if (value != null) orderService.setValue(value.nivelServicio()); });
        orderPolicy.valueProperty().addListener((obs, old, value) -> { if (value != null) policy.setValue(value); });
        showPage("Panel general");
        policy.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) selectedPolicy = newValue;
            if (newValue != null) orderPolicy.setValue(newValue);
            if (newValue != null && motorStarted.get()) runBackground("Actualizando política", () -> motor.cambiarEstrategia(newValue));
        });
        refreshTimeline = new Timeline(new KeyFrame(Duration.millis(350), event -> refreshSnapshots()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE); refreshTimeline.play();
    }

    public void conectarStage(Stage stage) {
        this.stage = stage;
        stage.setOnCloseRequest(event -> {
            if (!allowClose) { event.consume(); closeAsynchronously(); }
        });
    }

    private void buildNavigation() {
        for (String name : List.of("Panel general", "Pedidos", "Recepción", "Almacén", "Clientes")) {
            Button button = new Button(name);
            button.getStyleClass().add("nav-button"); button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> showPage(name)); navigation.getChildren().add(button);
        }
    }

    private void showPage(String name) {
        for (Node node : navigation.getChildren()) {
            node.getStyleClass().remove("active");
            if (node instanceof Button button && button.getText().equals(name)) button.getStyleClass().add("active");
        }
        content.getChildren().setAll(pages.get(name));
    }

    private ScrollPane page(String title, String subtitle, Node... nodes) {
        VBox body = new VBox(18); body.getStyleClass().add("page");
        VBox heading = new VBox(4); heading.getStyleClass().add("page-heading");
        Label titleLabel = new Label(title); titleLabel.getStyleClass().add("page-title");
        Label subtitleLabel = new Label(subtitle); subtitleLabel.getStyleClass().add("page-subtitle");
        heading.getChildren().addAll(titleLabel, subtitleLabel); body.getChildren().add(heading); body.getChildren().addAll(nodes);
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.getStyleClass().add("page-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); return scroll;
    }

    private Node dashboardPage() {
        HBox controls = new HBox(10); controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        motorStartButton = button("Iniciar motor", "primary", () -> startMotor());
        motorStatusIndicator = label("Motor detenido", "generator-stopped");
        Button demo = button("Cargar demostración", "secondary", this::loadDemo);
        controls.getChildren().addAll(label("Política vigente", "field-label"), policy, motorStartButton, motorStatusIndicator, demo);
        FlowPane cards = new FlowPane(12, 12);
        for (String key : List.of("En cola", "Espera de stock", "Espera de recursos", "Procesando", "Completados")) {
            VBox card = metric(key); HBox.setHgrow(card, Priority.ALWAYS); cards.getChildren().add(card);
        }
        resourceOccupancy = new VBox(12);
        VBox resourcesCard = card("Ocupación de recursos", resourceOccupancy);
        runtimeStatistics = label("Esperando actividad del motor", "helper-text");
        warehouseCapacitySummary = label("Capacidad del almacén: consultando…", "queue-note");
        Label planningNote = label("El despacho se recalcula con la política seleccionada. Los trabajadores concurrentes pueden modificar el orden de inicio.", "queue-note");
        return page("Panel general", "Estado en tiempo real de preparación, inventario y recursos.", controls, cards, warehouseCapacitySummary, resourcesCard, runtimeStatistics, planningNote);
    }

    private Node ordersPage() {
        refreshOrderClients();
        FlowPane accountLine = new FlowPane(10, 10, labeled("Cliente", orderClient), labeled("Servicio del pedido", orderService));
        FlowPane productLine = new FlowPane(10, 10, labeled("Producto", orderProduct), labeled("Cantidad entera", orderQuantity));
        Button add = button("Agregar línea", "secondary", this::addDraftLine);
        FlowPane actions = new FlowPane(8, 8, add,
                button("Quitar seleccionada", "danger", this::removeDraftLine),
                button("Vaciar borrador", "secondary", this::clearDraft),
                button("Registrar pedido", "primary", this::submitOrder));
        draftTable = new TableView<>(draftLines); draftTable.setPlaceholder(label("Agrega una o más líneas para preparar el pedido.", "helper-text"));
        draftTable.getColumns().setAll(column("Producto", row -> CatalogoProductos.porId(row.productoId()).nombre(), 0.64),
                column("Cantidad", row -> Integer.toString(row.cantidad()), 0.36)); draftTable.setPrefHeight(170);
        VBox form = card("Nuevo pedido manual", accountLine, productLine, actions, draftTable);

        orderPolicy.setMaxWidth(290);
        HBox plannerControls = new HBox(10, label("Criterio activo", "field-label"), orderPolicy);
        orderTable = ordersTable(); queueTable = planningTable();
        VBox allOrders = card("Pedidos recibidos", orderTable);
        orderDetailTable = orderDetailTable();
        orderDetailTitle = label("Selecciona un pedido para ver sus líneas y disponibilidad.", "helper-text");
        VBox orderDetail = card("Detalle del pedido seleccionado", orderDetailTitle, orderDetailTable);
        orderTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> updateOrderDetail(selected));
        VBox queue = card("Cola pendiente de despacho", label("Instantánea ordenada por el planificador; no garantiza cuál worker inicia primero.", "queue-note"), queueTable);
        VBox generator = generatorCard();
        return page("Pedidos", "Registra pedidos, observa el criterio de despacho y controla llegadas automáticas.", plannerControls, form, generator, queue, allOrders, orderDetail);
    }

    private VBox generatorCard() {
        FlowPane config = new FlowPane(9, 9, labeled("Intervalo (ms)", interval), labeled("Unidades mín.", unitsMin),
                labeled("Unidades máx.", unitsMax), labeled("Semilla", seed));
        Button startButton = button("Iniciar generador", "primary", () -> { });
        Button stopButton = button("Detener", "danger", () -> { });
        startButton.setOnAction(event -> startGenerator(startButton, stopButton));
        stopButton.setOnAction(event -> stopGenerator(startButton, stopButton));
        FlowPane controls = new FlowPane(10, 8, startButton, stopButton, generatorStatus, generatorCount);
        VBox box = card("Generación automática continua", label("Secuencia uniforme de los cinco servicios. La semilla fija productos y cantidades; los UUID cambian por ejecución.", "helper-text"), config, controls, lastGeneratorError);
        return box;
    }

    private Node receptionPage() {
        FlowPane fields = new FlowPane(12, 10, labeled("Producto del catálogo", receiptProduct), labeled("Cantidad entera", receiptQuantity));
        Button submit = button("Enviar a recepción", "primary", this::submitReceipt);
        VBox form = card("Registrar mercadería", label("La recepción entra a la cola acotada. Si no hay espacio, permanece pendiente; una cola llena se rechaza explícitamente.", "helper-text"), fields, submit);
        receiptTable = receiptTable();
        return page("Recepción", "Ingreso explícito de mercancía. El generador de pedidos no repone existencias.", form, card("Recepciones y motivos", receiptTable));
    }

    private Node warehousePage() {
        stockTable = stockTable(); locationTable = locationTable(); resourceTable = resourceTable();
        return page("Almacén", "Existencias físicas, reservas, ubicaciones y disponibilidad operativa.",
                card("Stock físico y disponible", stockTable), card("Ubicaciones y capacidad utilizada", locationTable),
                card("Capacidad y ocupación de recursos", resourceTable));
    }

    private Node clientsPage() {
        FlowPane fields = new FlowPane(10, 10, labeled("Nombre", accountName), labeled("Correo", accountEmail), labeled("Servicio por defecto", accountService));
        Button register = button("Registrar cliente", "primary", this::registerClient);
        VBox registerCard = card("Nueva cuenta", label("El correo se valida sin considerar mayúsculas ni espacios exteriores. No se usan contraseñas.", "helper-text"), fields, register);
        clientTable = clientTable();
        FlowPane update = new FlowPane(10, 10, labeled("Nuevo servicio por defecto", selectedAccountService),
                button("Actualizar cuenta seleccionada", "secondary", this::updateSelectedClient));
        return page("Clientes", "Cuentas temporales en memoria y selección para nuevos pedidos.", registerCard,
                card("Cuentas registradas", clientTable, update));
    }

    private void startMotor() {
        if (motorStartButton != null) motorStartButton.setDisable(true);
        runBackground("Iniciando motor", () -> {
            motor.cambiarEstrategia(selectedPolicy); motor.iniciar(); motorStarted.set(true);
            Platform.runLater(() -> setFooter("Motor activo · recepción e inventario disponibles"));
        }, () -> { if (motorStartButton != null) motorStartButton.setDisable(motor.snapshot().activo()); });
    }

    private void loadDemo() {
        if (demoLoaded || demoLoading) { setFooter("La demostración ya está cargada o en curso"); return; }
        demoLoading = true;
        runBackground("Cargando demostración", () -> {
            motor.cambiarEstrategia(selectedPolicy); motor.iniciar();
            Cliente demo = clients.buscarCorreo("demo@redxela.local").orElseThrow();
            reception.recibir(CatalogoProductos.porId("DOCUMENTOS"), 6);
            reception.recibir(CatalogoProductos.porId("FRAGIL"), 2);
            reception.recibir(CatalogoProductos.porId("VOLUMINOSO"), 3);
            motor.enviar(new Pedido(demo, NivelServicio.ECONOMICO, List.of(new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"), 5))), OrigenPedido.DEMOSTRACION);
            motor.enviar(new Pedido(demo, NivelServicio.EXPRES, List.of(new LineaPedido(CatalogoProductos.porId("FRAGIL"), 2))), OrigenPedido.DEMOSTRACION);
            motor.enviar(new Pedido(demo, NivelServicio.ESTANDAR, List.of(new LineaPedido(CatalogoProductos.porId("VOLUMINOSO"), 3),
                    new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"), 1))), OrigenPedido.DEMOSTRACION);
            motorStarted.set(true);
            Platform.runLater(() -> { demoLoaded = true; demoLoading = false; setFooter("Demostración en marcha · stock inicial explícito"); });
        }, () -> demoLoading = false);
    }

    private void addDraftLine() {
        try {
            Producto product = Objects.requireNonNull(orderProduct.getValue(), "Selecciona un producto");
            int amount = integer(orderQuantity, "cantidad", 1, 100_000);
            draftLines.add(new LineaPedido(product, amount)); orderQuantity.setText("1");
        } catch (RuntimeException ex) { setFooter("No se agregó la línea: " + ex.getMessage()); }
    }
    private void removeDraftLine() { LineaPedido selected = draftTable.getSelectionModel().getSelectedItem(); if (selected != null) draftLines.remove(selected); }
    private void clearDraft() { draftLines.clear(); }
    private void submitOrder() {
        Cliente client = orderClient.getValue();
        if (client == null) { setFooter("Registra y selecciona una cuenta antes de crear el pedido"); return; }
        if (draftLines.isEmpty()) { setFooter("Agrega al menos una línea al pedido"); return; }
        Pedido pedido;
        try { pedido = new Pedido(client, orderService.getValue(), List.copyOf(draftLines)); }
        catch (RuntimeException ex) { setFooter("Pedido inválido: " + ex.getMessage()); return; }
        runBackground("Enviando pedido", () -> {
            ensureMotor(); motor.enviar(pedido, OrigenPedido.MANUAL);
            Platform.runLater(() -> { draftLines.clear(); setFooter("Pedido manual aceptado · " + pedido.id().toString().substring(0, 8)); });
        });
    }
    private void submitReceipt() {
        Producto product = receiptProduct.getValue();
        final int amount;
        try { amount = integer(receiptQuantity, "cantidad", 1, 100_000); }
        catch (RuntimeException ex) { setFooter(ex.getMessage()); return; }
        runBackground("Enviando recepción", () -> {
            EstadoRecepcionVista result = reception.recibir(product, amount);
            Platform.runLater(() -> {
                if (result.estado() == EstadoRecepcion.RECHAZADA || result.estado() == EstadoRecepcion.RECHAZADA_COLA_LLENA)
                    setFooter("Recepción rechazada: " + result.motivo());
                else setFooter("Recepción aceptada · " + result.productoNombre() + " × " + result.cantidad());
            });
        });
    }
    private void registerClient() {
        String name = accountName.getText(), email = accountEmail.getText(); NivelServicio level = accountService.getValue();
        runBackground("Registrando cliente", () -> {
            Cliente client = clients.registrar(name, email, level);
            Platform.runLater(() -> { accountName.clear(); accountEmail.clear(); refreshClientViews(client); setFooter("Cuenta registrada: " + client.nombre()); });
        });
    }
    private void updateSelectedClient() {
        Cliente selected = clientTable == null ? null : clientTable.getSelectionModel().getSelectedItem();
        if (selected == null) { setFooter("Selecciona una cuenta en la tabla"); return; }
        NivelServicio service = selectedAccountService.getValue();
        runBackground("Actualizando cliente", () -> {
            Cliente changed = clients.actualizarServicio(selected.id(), service);
            Platform.runLater(() -> { refreshClientViews(changed); if (orderClient.getValue() != null && Objects.equals(orderClient.getValue().id(), changed.id())) orderService.setValue(changed.nivelServicio()); setFooter("Servicio de cuenta actualizado"); });
        });
    }
    private void startGenerator(Button start, Button stop) {
        final GeneradorPedidos.Configuracion config;
        try {
            long rate = longValue(interval, "intervalo", 1, Long.MAX_VALUE);
            int min = integer(unitsMin, "unidades mínimas", 1, 100_000);
            int max = integer(unitsMax, "unidades máximas", min, 100_000);
            long randomSeed = Long.parseLong(seed.getText().trim());
            config = new GeneradorPedidos.Configuracion(rate, min, max, randomSeed);
        } catch (RuntimeException ex) { setFooter("Configuración inválida: " + ex.getMessage()); return; }
        start.setDisable(true); stop.setDisable(true);
        runBackground("Iniciando generador", () -> {
            ensureMotor(); boolean started = generator.iniciar(config);
            Platform.runLater(() -> {
                start.setDisable(false); stop.setDisable(!generator.activo()); refreshClientViews(null);
                setFooter(started ? "Generador activo · la configuración se aplica en este inicio" : "El generador ya estaba activo o terminando");
            });
        }, () -> { start.setDisable(false); stop.setDisable(!generator.activo()); });
    }
    private void stopGenerator(Button start, Button stop) {
        start.setDisable(true); stop.setDisable(true);
        runBackground("Deteniendo generador", generator::detener, () -> {
            start.setDisable(false); stop.setDisable(false); setFooter("Generador detenido · pedidos aceptados conservados");
        });
    }

    private void ensureMotor() {
        if (!motorStarted.get()) { motor.cambiarEstrategia(selectedPolicy); motor.iniciar(); motorStarted.set(true); }
    }
    private void runBackground(String action, Runnable work) { runBackground(action, work, () -> { }); }
    private void runBackground(String action, Runnable work, Runnable completion) {
        if (closing.get()) return;
        setFooter(action + "…");
        try {
            background.execute(() -> {
                try { work.run(); }
                catch (RuntimeException ex) { Platform.runLater(() -> { setFooter("Error: " + message(ex)); completion.run(); }); return; }
                Platform.runLater(completion);
            });
        } catch (RejectedExecutionException ex) { setFooter("La aplicación se está cerrando"); }
    }

    private void refreshSnapshots() {
        if (closing.get()) return;
        SnapshotMotor work = motor.snapshot(); SnapshotInventario stock = inventory.snapshot();
        if (orderTable != null) {
            UUID selectedId = Optional.ofNullable(orderTable.getSelectionModel().getSelectedItem())
                    .map(row -> row.pedido().id()).orElse(null);
            orderTable.getItems().setAll(work.pedidos());
            if (selectedId != null) work.pedidos().stream().filter(row -> row.pedido().id().equals(selectedId))
                    .findFirst().ifPresentOrElse(row -> orderTable.getSelectionModel().select(row),
                            () -> updateOrderDetail(null));
            else updateOrderDetail(orderTable.getSelectionModel().getSelectedItem());
        }
        if (queueTable != null) queueTable.getItems().setAll(work.colaDespacho());
        if (receiptTable != null) receiptTable.getItems().setAll(reception.snapshot());
        if (stockTable != null) stockTable.getItems().setAll(stock.stock());
        if (locationTable != null) locationTable.getItems().setAll(stock.ubicaciones());
        if (resourceTable != null) resourceTable.getItems().setAll(Arrays.stream(TipoRecurso.values())
                .map(type -> new ResourceRow(type, work.estadisticas().recursos().get(type), work.estadisticas().utilizacionAcumulada().getOrDefault(type, 0d))).toList());
        if (resourceOccupancy != null) updateResourceBars(work.estadisticas());
        long occupied = stock.ubicaciones().stream().mapToLong(Almacen.Ubicacion::volumenUtilizado).sum();
        long totalCapacity = inventory.capacidadTotal();
        if (warehouseCapacitySummary != null) warehouseCapacitySummary.setText(String.format(Locale.ROOT,
                "Capacidad del almacén: %,d u. de volumen total · %,d ocupadas · %,d libres",
                totalCapacity, occupied, totalCapacity - occupied));
        if (motorStatusIndicator != null) {
            motorStatusIndicator.setText(work.activo() ? "Motor activo" : "Motor detenido");
            motorStatusIndicator.getStyleClass().setAll(work.activo() ? "generator-active" : "generator-stopped");
        }
        if (motorStartButton != null) motorStartButton.setDisable(work.activo() || closing.get());
        if (clientTable != null) {
            Cliente selected = clientTable.getSelectionModel().getSelectedItem(); UUID selectedId = selected == null ? null : selected.id();
            clientTable.getItems().setAll(clients.snapshot());
            if (selectedId != null) clientTable.getItems().stream().filter(c -> c.id().equals(selectedId)).findFirst()
                    .ifPresent(client -> clientTable.getSelectionModel().select(client));
        }
        if (generator.activo()) { generatorStatus.setText("Activo · ciclo continuo"); generatorStatus.getStyleClass().setAll("generator-active"); }
        else if (!generator.terminado()) { generatorStatus.setText("Deteniendo…"); generatorStatus.getStyleClass().setAll("status-wait"); }
        else { generatorStatus.setText("Detenido"); generatorStatus.getStyleClass().setAll("generator-stopped"); }
        generatorCount.setText(generator.aceptados() + " pedidos automáticos · " + levelCounts(generator.distribucion()));
        if (metrics.containsKey("En cola")) {
            EstadisticasMotor stat = work.estadisticas();
            metrics.get("En cola").setText(Integer.toString(stat.pendientes()));
            metrics.get("Espera de stock").setText(Integer.toString(stat.esperandoStock()));
            metrics.get("Espera de recursos").setText(Integer.toString(stat.esperandoRecursos()));
            metrics.get("Procesando").setText(Integer.toString(stat.procesandose()));
            metrics.get("Completados").setText(Integer.toString(stat.completados()));
            if (runtimeStatistics != null) runtimeStatistics.setText(String.format(Locale.ROOT,
                    "Política: %s · espera llegada→inicio: %.0f ms · procesamiento promedio: %.0f ms · pedidos automáticos: %d (%s)",
                    work.estrategia(), stat.esperaPromedioMillis(), stat.procesamientoPromedioMillis(), generator.aceptados(), levelCounts(generator.distribucion())));
        }
    }
    private void updateResourceBars(EstadisticasMotor stats) {
        resourceOccupancy.getChildren().clear();
        for (TipoRecurso type : TipoRecurso.values()) {
            GestorRecursos.EstadoRecurso state = stats.recursos().get(type); if (state == null) continue;
            HBox row = new HBox(12); row.getStyleClass().add("resource-row");
            Label name = label(type.etiqueta(), "resource-name");
            ProgressBar bar = new ProgressBar((double)(state.capacidad() - state.disponibles()) / state.capacidad()); HBox.setHgrow(bar, Priority.ALWAYS);
            Label count = label((state.capacidad() - state.disponibles()) + " / " + state.capacidad() + " ocupados", "resource-count");
            row.getChildren().addAll(name, bar, count); resourceOccupancy.getChildren().add(row);
        }
    }

    private TableView<EstadoPedidoMotor> ordersTable() {
        TableView<EstadoPedidoMotor> table = new TableView<>(); table.setPlaceholder(label("Todavía no hay pedidos.", "helper-text"));
        table.getColumns().setAll(column("Pedido", row -> row.pedido().id().toString().substring(0, 8), .1),
                column("Origen", row -> row.origen().name(), .11), column("Cliente", row -> row.pedido().cliente().nombre(), .17),
                column("Servicio", row -> row.pedido().nivelServicio().etiqueta(), .12), column("Unidades", row -> Integer.toString(row.pedido().totalUnidades()), .08),
                statusColumn("Estado", row -> stateText(row.estado()), row -> stateClass(row.estado()), .18),
                progressColumn("Progreso", .12), column("Restante", row -> row.tiempoRestanteEstimadoMillis() + " ms", .12));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(360); return table;
    }

    private TableView<OrderLineDetail> orderDetailTable() {
        TableView<OrderLineDetail> table = new TableView<>();
        table.setPlaceholder(label("Selecciona una fila en Pedidos recibidos.", "helper-text"));
        table.getColumns().setAll(column("Producto", OrderLineDetail::productName, .4),
                column("Solicitadas", row -> Integer.toString(row.requested()), .2),
                column("Disponibles para este pedido", row -> Integer.toString(row.available()), .24),
                column("Faltantes", row -> Integer.toString(row.missing()), .16));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(210); return table;
    }

    private void updateOrderDetail(EstadoPedidoMotor state) {
        if (orderDetailTable == null || orderDetailTitle == null) return;
        if (state == null) {
            orderDetailTitle.setText("Selecciona un pedido para ver sus líneas y disponibilidad.");
            orderDetailTable.getItems().clear(); return;
        }
        Pedido pedido = state.pedido();
        Map<String,Integer> requested = new TreeMap<>();
        pedido.lineas().forEach(line -> requested.merge(line.productoId(), line.cantidad(), Math::addExact));
        Map<String,Inventario.DisponibilidadLinea> availability = inventory.disponibilidadPedido(pedido.id(), requested);
        orderDetailTitle.setText("Pedido " + pedido.id().toString().substring(0,8) + " · "
                + stateText(state.estado()) + " · disponibilidad actualizada desde Inventario");
        orderDetailTable.getItems().setAll(requested.entrySet().stream().map(entry -> {
            Inventario.DisponibilidadLinea line = availability.get(entry.getKey());
            return new OrderLineDetail(CatalogoProductos.porId(entry.getKey()).nombre(),
                    line.solicitadas(), line.disponibles(), line.faltantes());
        }).toList());
    }
    private TableView<EntradaPlanificacion> planningTable() {
        TableView<EntradaPlanificacion> table = new TableView<>(); table.setPlaceholder(label("No hay pedidos listos para despacho.", "helper-text"));
        table.getColumns().setAll(column("Orden evaluado", row -> Integer.toString(queueTable == null ? 0 : queueTable.getItems().indexOf(row) + 1), .15),
                column("Pedido", row -> row.pedidoId().toString().substring(0, 8), .18), column("Unidades", row -> Integer.toString(row.unidades()), .15),
                column("Espera", row -> row.esperaMillis() + " ms", .18), column("Criterio efectivo", row -> planningCriterion(row), .34));
        table.setPrefHeight(240); return table;
    }
    private String planningCriterion(EntradaPlanificacion row) {
        return switch (policy.getValue()) {
            case PRIORIDAD_CON_ENVEJECIMIENTO -> "Prioridad efectiva " + row.prioridadEfectiva();
            case PRIORIDAD_ESTRICTA -> "Prioridad estricta";
            case MENOR_CANTIDAD_PRIMERO -> "Menor trabajo: " + row.unidades() + " unidades";
        };
    }
    private TableView<LineaPedido> lineTable() { return draftTable; }
    private TableView<EstadoRecepcionVista> receiptTable() {
        TableView<EstadoRecepcionVista> table = new TableView<>(); table.setPlaceholder(label("No hay recepciones registradas.", "helper-text"));
        table.getColumns().setAll(column("ID", row -> row.id().toString().substring(0, 8), .12), column("Producto", EstadoRecepcionVista::productoNombre, .2),
                column("Cantidad", row -> Integer.toString(row.cantidad()), .1), statusColumn("Estado", row -> row.estado().name(), row -> receiptClass(row.estado()), .24),
                column("Motivo", row -> row.motivo() == null ? "—" : row.motivo(), .34));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(390); return table;
    }
    private TableView<EstadoStock> stockTable() {
        TableView<EstadoStock> table = new TableView<>();
        table.getColumns().setAll(column("Producto", EstadoStock::nombre, .4), column("Físico", row -> Integer.toString(row.fisico()), .2),
                column("Reservado", row -> Integer.toString(row.reservado()), .2), column("Disponible", row -> Integer.toString(row.disponible()), .2));
        table.setPrefHeight(270); return table;
    }
    private TableView<Almacen.Ubicacion> locationTable() {
        TableView<Almacen.Ubicacion> table = new TableView<>();
        table.getColumns().setAll(column("Ubicación", Almacen.Ubicacion::id, .22),
                column("Producto", row -> row.productoId() == null ? "Vacía" : CatalogoProductos.porId(row.productoId()).nombre(), .32),
                column("Volumen usado", row -> Integer.toString(row.volumenUtilizado()), .23), column("Capacidad", row -> Integer.toString(row.capacidadVolumen()), .23));
        table.setPrefHeight(270); return table;
    }
    private TableView<ResourceRow> resourceTable() {
        TableView<ResourceRow> table = new TableView<>();
        table.getColumns().setAll(column("Recurso", row -> row.tipo().etiqueta(), .3), column("Capacidad", row -> Integer.toString(row.state().capacidad()), .14),
                column("Disponibles", row -> Integer.toString(row.state().disponibles()), .14), column("Operaciones propietarias", row -> Integer.toString(row.state().operacionesPropietarias().size()), .2),
                column("Utilización acumulada", row -> String.format(Locale.ROOT,"%.1f%%",row.utilization()*100), .22));
        table.setPrefHeight(300); return table;
    }
    private TableView<Cliente> clientTable() {
        TableView<Cliente> table = new TableView<>(); table.setPlaceholder(label("Registra la primera cuenta.", "helper-text"));
        table.getColumns().setAll(column("Cliente", Cliente::nombre, .28), column("Correo", Cliente::correo, .36),
                column("Servicio por defecto", row -> row.nivelServicio().etiqueta(), .24), column("ID", row -> row.id().toString().substring(0, 8), .12));
        table.setPrefHeight(330); return table;
    }
    private <T> TableColumn<T, String> column(String title, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(900 * width); column.setMinWidth(Math.min(70, 900 * width));
        column.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(!empty && item != null && !item.isBlank() ? new Tooltip(item) : null);
            }
        }); return column;
    }
    private <T> TableColumn<T, String> statusColumn(String title, Function<T, String> text,
                                                     Function<T, String> css, double width) {
        TableColumn<T, String> column = column(title, text, width);
        column.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) { setGraphic(null); setText(null); return; }
                T row = getTableView().getItems().get(getIndex()); Label pill = label(item, "status-pill"); pill.getStyleClass().add(css.apply(row));
                pill.setMaxWidth(Double.MAX_VALUE); pill.setTooltip(new Tooltip(item)); setGraphic(pill); setText(null);
            }
        }); return column;
    }

    private TableColumn<EstadoPedidoMotor, Number> progressColumn(String title, double width) {
        TableColumn<EstadoPedidoMotor, Number> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyDoubleWrapper(cell.getValue().progreso()));
        column.setCellFactory(c -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) setGraphic(null); else { bar.setProgress(value.doubleValue()); bar.setMaxWidth(Double.MAX_VALUE); setGraphic(bar); }
            }
        }); column.setMaxWidth(1f * Integer.MAX_VALUE * width); return column;
    }

    private VBox metric(String title) {
        Label value = label("0", "metric-value"); metrics.put(title, value);
        VBox box = new VBox(7, label(title, "metric-label"), value, label("Ejecución actual", "metric-foot"));
        box.getStyleClass().addAll("card", "metric-card"); return box;
    }
    private VBox card(String title, Node... children) {
        VBox box = new VBox(12); box.getStyleClass().add("card");
        if (title != null && !title.isBlank()) box.getChildren().add(label(title, "section-title"));
        box.getChildren().addAll(children); return box;
    }
    private VBox labeled(String name, Node control) {
        VBox field = new VBox(5, label(name, "field-label"), control); HBox.setHgrow(field, Priority.ALWAYS); control.setStyle("-fx-max-width: 100000px;"); return field;
    }
    private Label label(String text, String css) { Label l = new Label(text); l.getStyleClass().add(css); return l; }
    private Button button(String text, String css, Runnable action) {
        Button button = new Button(text); button.getStyleClass().add(css); button.setOnAction(event -> action.run()); return button;
    }
    private void refreshClientViews(Cliente selected) {
        refreshOrderClients(); if (selected != null) orderClient.setValue(selected);
        if (clientTable != null) clientTable.getItems().setAll(clients.snapshot());
    }
    private void refreshOrderClients() {
        Cliente selected = orderClient.getValue(); UUID selectedId = selected == null ? null : selected.id();
        orderClient.getItems().setAll(clients.snapshot());
        if (selectedId != null) clients.buscar(selectedId).ifPresent(orderClient::setValue);
        if (orderClient.getValue() == null) orderClient.getSelectionModel().selectFirst();
        if (orderClient.getValue() != null && orderService.getValue() == null) orderService.setValue(orderClient.getValue().nivelServicio());
    }
    private void setFooter(String text) { if (footerStatus != null) footerStatus.setText(text); }
    private static int integer(TextField field, String name, int minimum, int maximum) {
        try { int value = Integer.parseInt(field.getText().trim()); if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " debe estar entre " + minimum + " y " + maximum); return value; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " debe ser un entero"); }
    }
    private static long longValue(TextField field, String name, long minimum, long maximum) {
        try { long value = Long.parseLong(field.getText().trim()); if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " debe ser al menos " + minimum); return value; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " debe ser un entero"); }
    }
    private static String stateText(EstadoPedido state) {
        return switch(state) { case RECIBIDO -> "Pendiente despacho"; case ESPERANDO_STOCK -> "Esperando stock";
            case ESPERANDO_RECURSOS -> "Esperando recursos"; case EN_PROCESAMIENTO -> "Procesando";
            case FINALIZADO -> "Finalizado"; case CANCELADO -> "Cancelado"; };
    }
    private static String stateClass(EstadoPedido state) {
        return switch(state) { case FINALIZADO -> "status-good"; case ESPERANDO_STOCK, ESPERANDO_RECURSOS -> "status-wait";
            case EN_PROCESAMIENTO -> "status-work"; case CANCELADO -> "status-bad"; default -> "status-neutral"; };
    }
    private static String receiptClass(EstadoRecepcion state) {
        return switch(state) { case APLICADA -> "status-good"; case PENDIENTE_ESPACIO, EN_COLA -> "status-wait";
            case RECHAZADA, RECHAZADA_COLA_LLENA -> "status-bad"; default -> "status-neutral"; };
    }
    private static String levelCounts(Map<NivelServicio, Long> counts) {
        return String.format("Exprés %d / Prioritario %d / Estándar %d / Programado %d / Económico %d",
                counts.get(NivelServicio.EXPRES), counts.get(NivelServicio.PRIORITARIO), counts.get(NivelServicio.ESTANDAR),
                counts.get(NivelServicio.PROGRAMADO), counts.get(NivelServicio.ECONOMICO));
    }
    private static String message(RuntimeException e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private void closeAsynchronously() {
        if (!closeStarted.compareAndSet(false, true)) return;
        closing.set(true); if (refreshTimeline != null) refreshTimeline.stop(); root.setDisable(true);
        setFooter("Cerrando generador, inventario y motor…");
        Thread closer = new Thread(() -> {
            boolean interrupted = false;
            background.shutdown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!background.isTerminated() && System.nanoTime() < deadline) {
                try { background.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; background.shutdownNow(); }
            }
            if (!background.isTerminated()) {
                background.shutdownNow(); long forceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!background.isTerminated() && System.nanoTime() < forceDeadline) {
                    try { background.awaitTermination(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
            try { generator.close(); }
            catch (RuntimeException e) { Platform.runLater(() -> setFooter("Error al cerrar generador: " + message(e))); }
            finally {
                try { motor.close(); }
                catch (RuntimeException e) { Platform.runLater(() -> setFooter("Error al cerrar motor: " + message(e))); }
                if (interrupted) Thread.currentThread().interrupt();
                Platform.runLater(() -> { allowClose = true; stage.close(); });
            }
        }, "redxela-cierre"); closer.setDaemon(false); closer.start();
    }

    /** JavaFX llama este método al cerrar la plataforma; el cierre real se espera en el hilo dedicado. */
    public void detenerTimeline() { if (refreshTimeline != null) refreshTimeline.stop(); }

}
