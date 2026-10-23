package com.snowmcd.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

/** JavaFX application shell. */
public final class MainApp extends Application {

    private static final String CSS = "/com/snowmcd/ui/app.css";

    @Override
    public void start(Stage stage) {
        MainView view = new MainView(stage);
        Scene scene = new Scene(view.getRoot(), 1100, 700);
        scene.getStylesheets().add(Objects.requireNonNull(
                MainApp.class.getResource(CSS), "missing stylesheet").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("SnowMCD · Minecraft 全量下载器");
        stage.setMinWidth(940);
        stage.setMinHeight(620);
        try (InputStream icon = MainApp.class.getResourceAsStream("/com/snowmcd/ui/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (Exception e) {
            // the icon is cosmetic; never block startup
        }
        stage.show();
    }
}
