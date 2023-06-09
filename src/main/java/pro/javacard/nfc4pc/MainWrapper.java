package pro.javacard.nfc4pc;

import apdu4j.core.BIBO;
import apdu4j.core.CommandAPDU;
import apdu4j.core.HexUtils;
import apdu4j.core.ResponseAPDU;
import apdu4j.pcsc.CardBIBO;
import apdu4j.pcsc.SCard;
import apdu4j.pcsc.TerminalManager;
import joptsimple.OptionSet;

import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
        Thread shutdownThread = new Thread(() -> {
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

            if (opts.has(OPT_EMULATE)) {
                // Locate the reader
                TerminalManager manager = TerminalManager.getDefault();
                CardTerminal term = manager.getTerminal(opts.valueOf(OPT_READER));
                Card c = term.connect("DIRECT");


                // Check if reader responds with "sanity"
                byte[] getver = HexUtils.hex2bin("E000001800");
                byte[] ver = c.transmitControlCommand(SCard.CARD_CTL_CODE(3500), getver);
                System.out.printf("Control code: %s 0x%04d", SCard.CARD_CTL_CODE(23500), SCard.CARD_CTL_CODE(3500));
                if (ver.length > 3) {
                    System.out.println("Reader: " + new String(ver, StandardCharsets.UTF_8) + " (" + HexUtils.bin2hex(ver) + ")");
                    // Get emulation mode
                    byte[] enteremu = HexUtils.hex2bin("E000004003010000");
                    byte[] emu = c.transmitControlCommand(SCard.CARD_CTL_CODE(3500), enteremu);
                    System.out.println("Emu: " + new String(emu, StandardCharsets.UTF_8) + " (" + HexUtils.bin2hex(emu) + ")");
                    // Take me to google.com up to 00 18 (which is len)  is ACS header
                    byte[] writeemu = HexUtils.stringToBin("E0 00 00 60 1C 01 01 00 18 E1 10 06 00 03 0F D1 01 0B 55 01 67 6F 6F 67 6C 65 2E 63 6F 6D FE 00 00");
                    byte[] write = c.transmitControlCommand(SCard.CARD_CTL_CODE(3500), writeemu);
                    System.out.println("write: " + HexUtils.bin2hex(write));


                    byte[] reademu = HexUtils.hex2bin("E00000600600010034");
                    byte[] read = c.transmitControlCommand(SCard.CARD_CTL_CODE(3500), reademu);
                    System.out.println("read: " +  HexUtils.bin2hex(read));

                    // read emu

                    Thread.sleep(60000);

                } else {
                    System.err.println("Reader does not respond with sanity");
                    System.exit(1);
                }
                System.exit(0);
            }
            boolean ui = hasUI();

            // Run in loop
            boolean daemon = opts.has(OPT_DESKTOP) || opts.has(OPT_HEADLESS);

            if (opts.has(OPT_DEBUG)) {
                debug = true;
                System.setProperty("org.slf4j.simpleLogger.log.pro.javacard", "debug");
                System.setProperty("org.slf4j.simpleLogger.log.apdu4j.pcsc", "debug");
            }

            if (!ui && opts.has(OPT_DESKTOP)) {
                fail("No desktop available. Try headless mode with --headless --webhook");
            }

            if (!canOpenBrowser() && opts.has(OPT_DESKTOP) && !opts.has(OPT_QR)) {
                fail("Can not open URL-s. Try headless mode with --headless --webhook OR with --qr");
            }

            if (daemon) {
                if (opts.has(CommandLine.OPT_WEBHOOK))
                    System.err.println("Webhook URI: " + opts.valueOf(CommandLine.OPT_WEBHOOK));
                if (opts.has(CommandLine.OPT_UID_URL))
                    System.err.println("UID URI: " + opts.valueOf(CommandLine.OPT_UID_URL));
                if (opts.has(CommandLine.OPT_META_URL))
                    System.err.println("Meta URI: " + opts.valueOf(CommandLine.OPT_META_URL));
            }

            Runtime.getRuntime().addShutdownHook(shutdownThread);

            RuntimeConfig conf = new RuntimeConfig(opts.valueOf(OPT_UID_URL), opts.valueOf(OPT_META_URL), opts.valueOf(OPT_WEBHOOK), opts.valueOf(OPT_AUTHORIZATION));

            boolean showUI = opts.has(OPT_DESKTOP) && !opts.has(OPT_NO_GUI);
            // Configure
            NFC4PC.main(conf, shutdownThread, !daemon, showUI, opts.has(OPT_QR));

            if (!showUI) {
                NFC4PC app = new NFC4PC();
                app.init();
                app.waitOnThread();
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            System.err.println("No UI");
            Runtime.getRuntime().removeShutdownHook(shutdownThread);
        }
    }


    // See https://stackoverflow.com/questions/43669797/run-only-in-system-tray-with-no-dock-taskbar-icon-in-java
    private static boolean hasUI() {
        System.setProperty("apple.awt.UIElement", "true");
        Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
        return tk != null && !tk.getClass().getSimpleName().equals("HeadlessToolkit");
    }

    private static boolean canOpenBrowser() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
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
