package gt.redxela.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

/** Punto de entrada JavaFX. La composición del dominio permanece en los servicios existentes. */
public final class RedXelaApp extends Application {
    private RedXelaController controller;
    @Override public void start(Stage stage) throws Exception {
        URL layout = RedXelaApp.class.getResource("/gt/redxela/ui/redxela.fxml");
        FXMLLoader loader = new FXMLLoader(layout);
        Scene scene = new Scene(loader.load(), 1366, 768);
        scene.getStylesheets().add(RedXelaApp.class.getResource("/gt/redxela/ui/redxela.css").toExternalForm());
        stage.setTitle("REDXela | Centro de operaciones");
        stage.setMinWidth(900); stage.setMinHeight(600); stage.setScene(scene);
        controller = loader.getController();
        controller.conectarStage(stage);
        stage.show();
    }
    @Override public void stop() { if (controller != null) controller.detenerTimeline(); }
    public static void main(String[] args) { launch(args); }
}
