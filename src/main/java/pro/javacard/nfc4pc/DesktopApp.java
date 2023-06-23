package pro.javacard.nfc4pc;

import com.dustinredmond.fxtrayicon.FXTrayIcon;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Objects;

public class DesktopApp extends Application {
    static final Logger log = LoggerFactory.getLogger(DesktopApp.class);

    private static Thread shutdownHook;
    private static NFC4PC app;

    private FXTrayIcon icon;

    // GUI option
    private void setTooltip() {
        String msg = String.format("NFC4PC: %d actions for %d taps", MainWrapper.urlCounter.get() + MainWrapper.uidCounter.get() + MainWrapper.metaCounter.get() + MainWrapper.webhookCounter.get(), MainWrapper.tapCounter.get());
        // Called from init, thus can be null
        if (icon != null)
            icon.setTrayIconTooltip(msg);
    }

    private void notifyUser(String title, String message) {
        // FIXME: get notifications from app
        Platform.runLater(() -> icon.showInfoMessage(title, message));
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        // FIXME: first "similar from Google"
        icon = new FXTrayIcon(primaryStage, Objects.requireNonNull(getClass().getResource("icon.png")));
        icon.addExitItem("Exit", (event) -> {
            System.err.println("Exiting nfc4pc");
            if (shutdownHook != null)
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            MainWrapper.sendStatistics();
            Platform.exit(); // shutdown
            System.exit(0); // Exit
        });

        MenuItem about = new MenuItem("About NFC4PC");
        about.setOnAction((e) -> {
            app.openBrowser(URI.create("https://github.com/martinpaljak/NFC4PC/wiki"));
        });
        icon.addMenuItem(about);
        setTooltip();
        // No UI other than tray
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        icon.show();
    }

    @Override
    public void init() throws Exception {
        log.info("init()");
        // Start monitoring thread
    }

    @Override
    public void stop() throws Exception {
        log.info("stop()");
        //pcscMonitor.interrupt();
    }

    static void configure(NFC4PC app, Thread shutdownHook) {
        DesktopApp.shutdownHook = shutdownHook;
        DesktopApp.app = app;
        launch();
    }
}