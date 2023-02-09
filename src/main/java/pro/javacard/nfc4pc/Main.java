package pro.javacard.nfc4pc;

import apdu4j.core.*;
import apdu4j.pcsc.*;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import com.dustinredmond.fxtrayicon.FXTrayIcon;
import com.payneteasy.tlv.BerTlvParser;
import com.payneteasy.tlv.BerTlvs;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class Main extends Application implements PCSCMonitor {
    static final Logger log = LoggerFactory.getLogger(Main.class);

    // D2760000850101
    static final byte[] NDEF_AID = new byte[]{(byte) 0xD2, (byte) 0x76, (byte) 0x00, (byte) 0x00, (byte) 0x85, (byte) 0x01, (byte) 0x01};

    // Lets have a thread per reader and a monitoring thread, in addition to the UI thread. Many threads, yay!
    private final TerminalManager manager = TerminalManager.getDefault();
    private final Thread pcscMonitor = new Thread(new HandyTerminalsMonitor(manager, this));

    ConcurrentHashMap<String, ExecutorService> readerThreads = new ConcurrentHashMap<>();

    static ThreadLocal<CardTerminal> readers = ThreadLocal.withInitial(() -> null);

    void onReaderThread(String name, Runnable r) {
        readerThreads.computeIfAbsent(name, (n) -> Executors.newSingleThreadExecutor(new NamedReaderThreadFactory(n))).submit(r);
    }

    static final class NamedReaderThreadFactory implements ThreadFactory {
        final String n;

        public NamedReaderThreadFactory(String name) {
            n = name;
        }

        public Thread newThread(Runnable r) {
            return new Thread(r, n);
        }
    }

    private FXTrayIcon icon;
    private static Thread shutdownHook;

    private Map<String, Boolean> readerStates = new HashMap<>();

    @Override
    public void start(Stage primaryStage) throws Exception {
        // FIXME: first "similar from Google"
        icon = new FXTrayIcon(primaryStage, Objects.requireNonNull(getClass().getResource("icon.png")));
        icon.addExitItem("Exit NFC4PC", (event) -> {
            System.out.println("Exiting");
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            MainWrapper.sendStatistics().run(); // Blocks
            Platform.exit(); // shutdown
            System.exit(0); // Exit
        });
        // No UI other than tray
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        icon.show();
    }

    @Override
    public void init() throws Exception {
        pcscMonitor.setDaemon(true);
        pcscMonitor.setName("PC/SC monitor");
        pcscMonitor.start();
    }

    @Override
    public void stop() throws Exception {
        pcscMonitor.interrupt();
    }

    public void onUrl(String url) {
        // Called on reader thread
        log.debug("Opening URL " + url);

        // Increase counter for statistics
        MainWrapper.counter.incrementAndGet();

        // Show notification
        Platform.runLater(() -> icon.showInfoMessage("Opening", url));

        // Launch browser
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI.create(url));
            } else {
                // report error
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(OptionSet opts, Thread theHook) {
        // normal exit via menu removes hook
        shutdownHook = theHook;
        launch();
    }

    @Override
    public void readerListChanged(List<PCSCReader> list) {
        // Track changes. PC/SC monitor thread
        Map<String, Boolean> newStates = new HashMap<>();
        list.forEach(e -> newStates.put(e.getName(), e.isPresent()));

        for (String n : newStates.keySet()) {
            if (newStates.get(n) && !readerStates.getOrDefault(n, false)) {
                log.debug("Detected change in reader " + n);
                // Windows needs a bit of time TODO: add loop
                if (com.sun.jna.Platform.isWindows()) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        log.debug("Interrupted");
                    }
                }
                // Try to read
                onReaderThread(n, this::tryToRead);
            }
            // Store state of _this_ notification
            readerStates.put(n, newStates.get(n));
        }
    }

    void tryToRead() {
        // This is called on the named thread of the reader.
        String n = Thread.currentThread().getName();

        // We manually open the instance
        CardTerminal t = readers.get();
        if (t == null) {
            t = LoggingCardTerminal.getInstance(manager.getTerminal(n));
            readers.set(t);
        }

        Card c = null;
        try {
            // Try to get exclusive access for a second
            // c = LoggingCardTerminal.getInstance(manager.getTerminal(reader)).connect("EXCLUSIVE;*");
            c = t.connect("EXCLUSIVE;*");
            // get UID
            APDUBIBO b = new APDUBIBO(CardBIBO.wrap(c));
            var uid = CardCommands.getUID(b);
            var type2 = CardCommands.getType2(b);
            var url = CardCommands.getType4(b);
            log.info("Read URL: {}", url);

            // If URL present, open it, otherwise open UID url if present.
            url.ifPresent(this::onUrl);


        } catch (Exception e) {
            log.error("Could not connect to or read: " + e.getMessage(), e);
        } finally {
            if (c != null)
                try {
                    c.disconnect(true);
                } catch (CardException e) {
                    log.error("Could not disconnect: " + e.getMessage(), e);
                }
        }
    }

    @Override
    public void readerListErrored(Throwable throwable) {
        System.out.println("PC/SC Error: " + throwable.getMessage());
    }

    public static short getShort(byte[] bArray, short bOff) throws ArrayIndexOutOfBoundsException, NullPointerException {
        return (short) (((short) bArray[bOff] << 8) + ((short) bArray[bOff + 1] & 255));
    }

    public static byte[] concatenate(byte[]... args) {
        int length = 0;
        int pos = 0;
        byte[][] var3 = args;
        int var4 = args.length;

        int var5;
        for (var5 = 0; var5 < var4; ++var5) {
            byte[] arg = var3[var5];
            length += arg.length;
        }

        byte[] result = new byte[length];
        byte[][] var9 = args;
        var5 = args.length;

        for (int var10 = 0; var10 < var5; ++var10) {
            byte[] arg = var9[var10];
            System.arraycopy(arg, 0, result, pos, arg.length);
            pos += arg.length;
        }

        return result;
    }


    public static void main(String[] args) {
        byte[] v = HexUtils.hex2bin("0312D1010E55046B7962657270756E6B2E6E6574FE000000000000000000000000000000000000000000000000000000000000000000000000000000");
        BerTlvParser parser = new BerTlvParser();
        BerTlvs result = parser.parse(v);
        System.out.println(result);
    }
}