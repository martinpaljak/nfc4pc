package pro.javacard.nfc4pc;

import joptsimple.OptionSet;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

// Static entrypoint and CLI handling
public class MainWrapper extends CommandLine {
    static final URI reportURL = URI.create("https://javacard.pro/nfc4pc/stats");
    static AtomicLong tapCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong urlCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong uidCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong metaCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics
    static AtomicLong webhookCounter = new AtomicLong(0); // Atomic so we can have daemon threads reading it for statistics

    static boolean debug = false;
    public static void main(String[] args) {
        // Trap ctrl-c and similar signals
        Thread t = new Thread(() -> {
            System.err.println("Ctrl-C, quitting nfc4pc");
            sendStatistics();
        });

        try {
            OptionSet opts = null;
            try {
                opts = CommandLine.parseArguments(args);
            } catch (IOException e) {
                fail("Could not start: " + e.getMessage());
            }
            boolean ui = hasUI();

            if (opts.has(OPT_DEBUG)) {
                debug = true;
                System.setProperty("org.slf4j.simpleLogger.log.pro.javacard", "debug");
                System.setProperty("org.slf4j.simpleLogger.log.apdu4j.pcsc", "debug");
            }

            if (!ui && !(opts.has(CommandLine.OPT_WEBHOOK) || opts.has(OPT_GET))) {
                fail("No desktop available, must run with --webhook or -g/--get");
            }

            if (opts.has(CommandLine.OPT_WEBHOOK))
                System.err.println("Webhook URI: " + opts.valueOf(CommandLine.OPT_WEBHOOK));
            if (opts.has(CommandLine.OPT_UID_URL))
                System.err.println("UID URI: " + opts.valueOf(CommandLine.OPT_UID_URL));
            if (opts.has(CommandLine.OPT_META_URL))
                System.err.println("Meta URI: " + opts.valueOf(CommandLine.OPT_META_URL));

            Runtime.getRuntime().addShutdownHook(t);

            boolean headless = opts.has(CommandLine.OPT_NO_GUI) || opts.has(CommandLine.OPT_HEADLESS) || opts.has(OPT_GET);

            RuntimeConfig conf = new RuntimeConfig(opts.valueOf(OPT_UID_URL), opts.valueOf(OPT_META_URL), opts.valueOf(OPT_WEBHOOK), opts.valueOf(OPT_AUTHORIZATION));
            // XXX
            NFC4PC.main(conf, t, headless);
            if (headless) {
                NFC4PC app = new NFC4PC();
                app.init();
                app.waitOnThread();
            } else {
                NFC4PC.launch();
            }
            // Webserver
        } catch (Throwable ex) {
            ex.printStackTrace();
            System.err.println("No UI");
            Runtime.getRuntime().removeShutdownHook(t);
        }
    }


    // See https://stackoverflow.com/questions/43669797/run-only-in-system-tray-with-no-dock-taskbar-icon-in-java
    private static boolean hasUI() {
        System.setProperty("apple.awt.UIElement", "true");
        Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
        return tk != null && !tk.getClass().getSimpleName().equals("HeadlessToolkit");
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
