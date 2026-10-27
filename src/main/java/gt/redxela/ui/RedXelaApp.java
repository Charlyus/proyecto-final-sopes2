package gt.redxela.ui;

import gt.redxela.almacenamiento.Almacen;
import gt.redxela.concurrencia.*;
import gt.redxela.inventario.*;
import gt.redxela.modelo.*;
import gt.redxela.planificacion.EstrategiaPlanificacion;
import gt.redxela.simulacion.*;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/** Todas las acciones visuales corren en EDT; la cola de recepción usa offer no bloqueante. */
public final class RedXelaApp {
    private final DefaultTableModel stockModel=model("Producto","Físico","Reservado","Disponible");
    private final DefaultTableModel locationModel=model("Ubicación","Producto","Volumen usado","Capacidad");
    private final DefaultTableModel receiptModel=model("ID","Producto","Cantidad","Estado","Motivo");
    private final DefaultTableModel orderModel=model("ID","Servicio","Unidades","Estado","Progreso","Restante ms");
    private final DefaultTableModel resourceModel=model("Recurso","Capacidad","Disponibles","Operaciones","Utilización");
    private final JComboBox<EstrategiaPlanificacion> policy=new JComboBox<>(EstrategiaPlanificacion.values());
    private final JComboBox<Producto> receiptProduct=new JComboBox<>(CatalogoProductos.todos().toArray(Producto[]::new));
    private final JComboBox<Producto> lineProduct=new JComboBox<>(CatalogoProductos.todos().toArray(Producto[]::new));
    private final JComboBox<NivelServicio> service=new JComboBox<>(NivelServicio.values());
    private final JSpinner receiptQuantity=new JSpinner(new SpinnerNumberModel(1,1,100_000,1));
    private final JSpinner lineQuantity=new JSpinner(new SpinnerNumberModel(1,1,100_000,1));
    private final DefaultListModel<String> draftModel=new DefaultListModel<>();
    private final List<LineaPedido> draftLines=new ArrayList<>();
    private final JLabel stats=new JLabel("Motor detenido · recepción disponible en cola limitada");

    private final GestorRecursos resources=new GestorRecursos();
    private final ArrayBlockingQueue<SolicitudRecepcion> queue=new ArrayBlockingQueue<>(12);
    private final Recepcion reception;
    private final Inventario inventory;
    private final MotorSimulacion motor;
    private Timer timer;
    private boolean started;
    private boolean demoLoaded;

    private RedXelaApp() {
        Almacen warehouse=new Almacen(List.of(100,100,100,100,100));
        reception=new Recepcion(queue,warehouse.capacidadTotal());
        inventory=new Inventario(queue,reception,warehouse,resources);
        motor=new MotorSimulacion(3,new TiempoSimulado(250),EstrategiaPlanificacion.PRIORIDAD_ESTRICTA,10_000,resources);
        motor.conectarInventario(inventory);
    }
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new RedXelaApp().mostrar()); }

    private void mostrar() {
        JFrame frame=new JFrame("REDXela | Recepción, inventario y preparación");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(1080,720); frame.setLocationRelativeTo(null);
        JButton start=new JButton("Iniciar motor");
        JButton demo=new JButton("Cargar demostración con stock inicial");
        start.addActionListener(e -> iniciarMotor()); demo.addActionListener(e -> cargarDemo());
        policy.addActionListener(e -> { if(started) motor.cambiarEstrategia((EstrategiaPlanificacion)policy.getSelectedItem()); });
        JPanel header=new JPanel(new FlowLayout(FlowLayout.LEFT));
        header.add(new JLabel("Política:")); header.add(policy); header.add(start); header.add(demo);
        frame.add(header,BorderLayout.NORTH);
        frame.add(construirPaneles(),BorderLayout.CENTER); frame.add(stats,BorderLayout.SOUTH);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if(timer!=null) timer.stop();
                new Thread(motor::close,"redxela-cierre-ui").start();
                frame.dispose();
            }
        });
        timer=new Timer(300,e -> actualizarSnapshots()); timer.start();
        frame.setVisible(true);
    }

    private JComponent construirPaneles() {
        JPanel receptionForm=new JPanel(new FlowLayout(FlowLayout.LEFT));
        receptionForm.setBorder(BorderFactory.createTitledBorder("Registrar recepción"));
        JButton receive=new JButton("Enviar a recepción");
        receive.addActionListener(e -> {
            Producto product=(Producto)receiptProduct.getSelectedItem();
            EstadoRecepcionVista result=reception.recibir(product,(Integer)receiptQuantity.getValue());
            if(result.estado()==EstadoRecepcion.RECHAZADA || result.estado()==EstadoRecepcion.RECHAZADA_COLA_LLENA)
                JOptionPane.showMessageDialog(null,result.motivo(),"Recepción rechazada",JOptionPane.WARNING_MESSAGE);
            actualizarSnapshots();
        });
        receptionForm.add(receiptProduct); receptionForm.add(new JLabel("Cantidad:"));
        receptionForm.add(receiptQuantity); receptionForm.add(receive);

        JPanel orderForm=new JPanel(new BorderLayout(4,4));
        orderForm.setBorder(BorderFactory.createTitledBorder("Pedido de cliente de demostración"));
        JPanel addLine=new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add=new JButton("Agregar línea");
        add.addActionListener(e -> {
            Producto product=(Producto)lineProduct.getSelectedItem(); int amount=(Integer)lineQuantity.getValue();
            draftLines.add(new LineaPedido(product,amount));
            draftModel.addElement(product.nombre()+" × "+amount); actualizarSnapshots();
        });
        addLine.add(lineProduct); addLine.add(new JLabel("Cantidad:")); addLine.add(lineQuantity); addLine.add(add);
        JButton submit=new JButton("Registrar pedido");
        submit.addActionListener(e -> registrarPedido());
        JButton clear=new JButton("Limpiar líneas");
        clear.addActionListener(e -> { draftLines.clear(); draftModel.clear(); });
        JPanel orderActions=new JPanel(new FlowLayout(FlowLayout.LEFT));
        orderActions.add(new JLabel("Servicio:")); orderActions.add(service); orderActions.add(submit); orderActions.add(clear);
        orderForm.add(addLine,BorderLayout.NORTH); orderForm.add(new JScrollPane(new JList<>(draftModel)),BorderLayout.CENTER);
        orderForm.add(orderActions,BorderLayout.SOUTH);

        JPanel forms=new JPanel(new GridLayout(2,1)); forms.add(receptionForm); forms.add(orderForm);
        JTabbedPane tabs=new JTabbedPane();
        tabs.addTab("Pedidos",new JScrollPane(table(orderModel)));
        tabs.addTab("Existencias",new JScrollPane(table(stockModel)));
        tabs.addTab("Ubicaciones",new JScrollPane(table(locationModel)));
        tabs.addTab("Recepciones",new JScrollPane(table(receiptModel)));
        tabs.addTab("Recursos",new JScrollPane(table(resourceModel)));
        JSplitPane vertical=new JSplitPane(JSplitPane.VERTICAL_SPLIT,forms,tabs); vertical.setResizeWeight(.38);
        return vertical;
    }

    private void iniciarMotor() {
        if(started) return;
        motor.cambiarEstrategia((EstrategiaPlanificacion)policy.getSelectedItem());
        motor.iniciar(); started=true;
        stats.setText("Motor activo · cola de recepción limitada a 12 solicitudes");
        actualizarSnapshots();
    }

    private void cargarDemo() {
        if(demoLoaded) return;
        iniciarMotor();
        // Stock inicial explícito: docs 6, frágil 2 y voluminoso 3 unidades.
        reception.recibir(CatalogoProductos.porId("DOCUMENTOS"),6);
        reception.recibir(CatalogoProductos.porId("FRAGIL"),2);
        reception.recibir(CatalogoProductos.porId("VOLUMINOSO"),3);
        Cliente demo=new Cliente("Cliente demostración","DEMO-001");
        motor.enviar(new Pedido(demo,NivelServicio.ECONOMICO,List.of(new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"),5))));
        motor.enviar(new Pedido(demo,NivelServicio.EXPRES,List.of(new LineaPedido(CatalogoProductos.porId("FRAGIL"),2))));
        motor.enviar(new Pedido(demo,NivelServicio.ESTANDAR,List.of(
                new LineaPedido(CatalogoProductos.porId("VOLUMINOSO"),3),
                new LineaPedido(CatalogoProductos.porId("DOCUMENTOS"),1))));
        demoLoaded=true; actualizarSnapshots();
    }

    private void registrarPedido() {
        if(!started) {
            JOptionPane.showMessageDialog(null,"Inicia el motor antes de registrar pedidos.","Motor detenido",JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if(draftLines.isEmpty()) {
            JOptionPane.showMessageDialog(null,"Agrega al menos una línea al pedido.","Pedido incompleto",JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            motor.enviar(new Pedido(new Cliente("Cliente demostración","DEMO-001"),
                    (NivelServicio)service.getSelectedItem(),List.copyOf(draftLines)));
            draftLines.clear(); draftModel.clear(); actualizarSnapshots();
        } catch(RuntimeException ex) {
            JOptionPane.showMessageDialog(null,ex.getMessage(),"No se pudo registrar el pedido",JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actualizarSnapshots() {
        SnapshotInventario stock=inventory.snapshot(); SnapshotMotor work=motor.snapshot();
        replace(stockModel,stock.stock().stream().map(s -> new Object[]{s.nombre(),s.fisico(),s.reservado(),s.disponible()}).toList());
        Map<String,Producto> products=new HashMap<>(); CatalogoProductos.todos().forEach(p -> products.put(p.id(),p));
        replace(locationModel,stock.ubicaciones().stream().map(u -> new Object[]{u.id(),
                u.productoId()==null?"—":products.get(u.productoId()).nombre(),u.volumenUtilizado(),u.capacidadVolumen()}).toList());
        replace(receiptModel,reception.snapshot().stream().map(r -> new Object[]{r.id().toString().substring(0,8),
                r.productoNombre(),r.cantidad(),r.estado(),r.motivo()}).toList());
        replace(resourceModel,work.estadisticas().recursos().entrySet().stream().map(e -> {
            var resource=e.getValue();
            return new Object[]{e.getKey().etiqueta(),resource.capacidad(),resource.disponibles(),
                    resource.operacionesPropietarias(),String.format("%.1f%%",work.estadisticas().utilizacionAcumulada().get(e.getKey())*100)};
        }).toList());
        replace(orderModel,work.pedidos().stream().map(o -> new Object[]{o.pedido().id().toString().substring(0,8),
                o.pedido().nivelServicio(),o.pedido().totalUnidades(),o.estado(),String.format("%.0f%%",o.progreso()*100),
                o.tiempoRestanteEstimadoMillis()}).toList());
        EstadisticasMotor x=work.estadisticas();
        stats.setText(String.format("%s · pendientes listos %d · esperando stock %d · esperando recursos %d · procesando %d · completados %d · espera media %.0f ms · proceso medio %.0f ms",
                started?"Motor activo":"Motor detenido",x.pendientes(),x.esperandoStock(),x.esperandoRecursos(),
                x.procesandose(),x.completados(),x.esperaPromedioMillis(),x.procesamientoPromedioMillis()));
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns,0) { @Override public boolean isCellEditable(int row,int column){return false;} };
    }
    private static JTable table(DefaultTableModel model) { JTable t=new JTable(model); t.setFillsViewportHeight(true); return t; }
    private static void replace(DefaultTableModel model,List<Object[]> rows) {
        model.setRowCount(0); rows.forEach(model::addRow);
    }
}
