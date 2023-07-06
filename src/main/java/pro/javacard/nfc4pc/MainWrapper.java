package pro.javacard.nfc4pc;

import joptsimple.OptionSet;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

// Static entrypoint and CLI handling
public class MainWrapper extends CLIOptions {
    static final URI reportURL = URI.create("https://javacard.pro/nfc4pc/stats");
    static AtomicLong tapCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong urlCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong uidCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong metaCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong webhookCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics

    public static void main(String[] args) {
        // Prevent dock icon on macOS
        // See https://stackoverflow.com/questions/43669797/run-only-in-system-tray-with-no-dock-taskbar-icon-in-java
        System.setProperty("apple.awt.UIElement", "true");

        // Trap ctrl-c and similar signals
        Thread shutdownThread = new Thread(() -> {
            System.err.println("Ctrl-C, quitting nfc4pc");
            sendStatistics();
        });


        OptionSet opts = null;
        try {
            opts = CLIOptions.parseArguments(args);
        } catch (IOException e) {
            fail("Could not parse arguments: " + e.getMessage());
        }

        if (opts.has(OPT_DEBUG)) {
            System.setProperty("org.slf4j.simpleLogger.log.pro.javacard", "debug");
            System.setProperty("org.slf4j.simpleLogger.log.apdu4j.pcsc", "debug");
        }

        // Quick CLI hack - print QR code.
        if (opts.has(OPT_GO)) {
            NFC4PC.openBrowser(opts.valueOf(OPT_GO), opts);
            System.exit(0);
        }
        if (opts.hasArgument(OPT_QR)) {
            System.out.println(new QRCode().generate(opts.valueOf(OPT_QR).toString()));
            System.exit(0);
        }

        if (opts.has(OPT_EMULATE)) {
            try {
                Emulation.emulate(opts);
            } catch (Exception e) {
                System.err.println("Emulation failed: " + e.getMessage());
                System.exit(2);
            }
        } else {
            NFC4PC app = new NFC4PC(opts, shutdownThread);
            NFCReader reader = new NFCReader(app);
            Runtime.getRuntime().addShutdownHook(shutdownThread);

            if (opts.has(OPT_DESKTOP)) {
                try {
                    if (!hasUI())
                        fail("No desktop available. Try headless mode with --headless");
                    DesktopApp.configure(app, shutdownThread);
                } catch (Throwable ex) {
//                    ex.printStackTrace();
                    System.err.println("No desktop UI available.");
                    System.err.println("Please check https://github.com/martinpaljak/NFC4PC/wiki/JavaFX for troubleshooting");
                    app.openBrowser(URI.create("https://github.com/martinpaljak/NFC4PC/wiki/JavaFX"));

                    Runtime.getRuntime().removeShutdownHook(shutdownThread);
                    System.exit(1);
                }
            } else {
                try {
                    reader.waitForever();
                } catch (InterruptedException e) {
                    System.err.println("Interrupted");
                }
            }
        }
    }



    private static boolean hasUI() {
        try {
            Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
            return tk != null && !tk.getClass().getSimpleName().equals("HeadlessToolkit");
        } catch (Throwable e) {
            System.err.println("JavaFX probably not available");
            return false;
        }
    }

    private static boolean canOpenBrowser(OptionSet opts) {
        boolean env = System.getenv("BROWSER") != null && Files.isExecutable(Paths.get(System.getenv("BROWSER")));
        boolean cmd = opts.hasArgument(OPT_BROWSER) && Files.isExecutable(Paths.get(opts.valueOf(OPT_BROWSER)));
        boolean desktop = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        return env || cmd || desktop;
    }


    static void sendStatistics() {
        HashMap<String, String> p = new HashMap<>();
        for (String s : Arrays.asList("os.name", "os.version", "os.arch", "java.version", "java.vendor")) {
            p.put(s, System.getProperty(s, "unknown"));
        }
        p.put("tap", String.valueOf(tapCounter.get()));
        p.put("url", String.valueOf(urlCounter.get()));
        p.put("uid", String.valueOf(uidCounter.get()));
        p.put("meta", String.valueOf(metaCounter.get()));
        p.put("webhook", String.valueOf(webhookCounter.get()));

        WebHooks.fireAndForget(reportURL, p);
        tapCounter.set(0);
        urlCounter.set(0);
        uidCounter.set(0);
        metaCounter.set(0);
        webhookCounter.set(0);
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
