package pro.javacard.nfc4pc;

import apdu4j.core.APDUBIBO;
import apdu4j.core.HexUtils;
import apdu4j.pcsc.*;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import com.dustinredmond.fxtrayicon.FXTrayIcon;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class NFC4PC extends Application implements PCSCMonitor {
    static final Logger log = LoggerFactory.getLogger(NFC4PC.class);

    // D2760000850101
    static final byte[] NDEF_AID = new byte[]{(byte) 0xD2, (byte) 0x76, (byte) 0x00, (byte) 0x00, (byte) 0x85, (byte) 0x01, (byte) 0x01};

    // Let's have a thread per reader and a monitoring thread, in addition to the UI thread. Many threads, yay!
    private final TerminalManager manager = TerminalManager.getDefault();
    private final Thread pcscMonitor = new Thread(new HandyTerminalsMonitor(manager, this));

    // CardTerminal instance is kept per thread.
    private final ConcurrentHashMap<String, ExecutorService> readerThreads = new ConcurrentHashMap<>();
    static ThreadLocal<CardTerminal> readers = ThreadLocal.withInitial(() -> null);

    private static Thread shutdownHook;
    private static boolean headless;
    private static boolean single = true;
    private static RuntimeConfig conf;

    // This is a fun exercise, we have a thread per reader and use the thread name for logging as well as reader access.
    void onReaderThread(String name, Runnable r) {
        readerThreads.computeIfAbsent(name, (n) -> Executors.newSingleThreadExecutor(new NamedReaderThreadFactory(n))).submit(r);
    }

    // ThreadFactory with single purpose: makes threads with a given name.
    static final class NamedReaderThreadFactory implements ThreadFactory {
        final String n;

        public NamedReaderThreadFactory(String name) {
            n = name;
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, n);
            t.setDaemon(true);
            return t;
        }
    }

    private FXTrayIcon icon;

    private final Map<String, Boolean> readerStates = new HashMap<>();

    // GUI option
    private void setTooltip() {
        String msg = String.format("NFC4PC: %d actions for %d taps", MainWrapper.urlCounter.get() + MainWrapper.uidCounter.get() + MainWrapper.metaCounter.get() + MainWrapper.webhookCounter.get(), MainWrapper.tapCounter.get());
        if (headless) {
            log.info(msg);
        } else {
            // Called from init, thus can be null
            if (icon != null)
                icon.setTrayIconTooltip(msg);
        }
    }

    private void notifyUser(String title, String message) {
        if (headless) {
            System.err.println(title + ": " + message);
        } else {
            Platform.runLater(() -> icon.showInfoMessage(title, message));
        }
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
            openUrl(URI.create("https://github.com/martinpaljak/NFC4PC/wiki"));
        });
        icon.addMenuItem(about);
        setTooltip();
        // No UI other than tray
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        icon.show();
    }

    @Override
    public void init() throws Exception {
        // Start monitoring thread
        pcscMonitor.setDaemon(true);
        pcscMonitor.setName("PC/SC monitor");
        pcscMonitor.start();
    }

    @Override
    public void stop() throws Exception {
        pcscMonitor.interrupt();
    }

    public static void main(RuntimeConfig config, Thread shutdownHook, boolean single, boolean showUI) {
        // Normal exit via menu removes hook
        NFC4PC.shutdownHook = shutdownHook;
        NFC4PC.headless = !showUI;
        NFC4PC.conf = config;
        NFC4PC.single = single;

        if (showUI) {
            // Icon, thank you
            launch();
        }
    }

    @Override
    public void readerListChanged(List<PCSCReader> list) {
        // Track changes. PC/SC monitor thread
        boolean firstRun = readerStates.isEmpty(); // Require fresh tap

        Map<String, Boolean> newStates = new HashMap<>();
        list.forEach(e -> newStates.put(e.getName(), e.isPresent()));

        for (PCSCReader e : list) {
            String n = e.getName();
            if (e.isExclusive()) {
                log.debug("Ignoring exclusively in use reader \"{}\"", n);
            } else if (newStates.get(n) && !readerStates.getOrDefault(n, false) && !firstRun) {
                log.debug("Detected change in reader \"{}\"", n);
                MainWrapper.tapCounter.incrementAndGet();
                setTooltip();

                // Try to read
                onReaderThread(n, this::tryToRead);
            }
            // Store state of _this_ notification
            readerStates.put(n, newStates.get(n));
        }
    }

    static String uid2str(byte[] uid) {
        return HexUtils.bin2hex(uid);
    }

    void openUrl(URI uri) {
        if (single) {
            System.out.println(uri);
            if (shutdownHook != null)
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            System.exit(0);
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri);
            } else {
                // report error
                log.error("No desktop?");
                System.exit(1);
            }
        } catch (IOException e) {
            log.error("Could not launch URL: " + e.getMessage(), e);
        }
    }


    void onTap(String reader, byte[] uid, String url) {
        try {
            if (conf.webhook().isPresent()) {
                LinkedHashMap<String, String> payload = new LinkedHashMap<>();
                payload.put("uid", uid2str(uid));
                if (url != null)
                    payload.put("url", url);
                MainWrapper.webhookCounter.incrementAndGet();
                try {
                    if (!WebHooks.post(conf.webhook().get(), payload, conf.auth().orElse(null)).call()) {
                        notifyUser(reader, "Failed to post webhook to " + conf.webhook().get());
                    }
                } catch (Exception e) {
                    notifyUser(reader, "Failed to post webhook to " + conf.webhook().get());
                }
            } else {
                // Open
                if (conf.meta().isPresent()) {
                    MainWrapper.metaCounter.incrementAndGet();
                    URI target = appendUri(conf.meta().get(), "uid", uid2str(uid));
                    if (url != null) {
                        target = appendUri(target, "url", URLEncoder.encode(url, StandardCharsets.UTF_8));
                    }
                    openUrl(target);
                } else {
                    if (url == null) {
                        if (conf.uid().isPresent()) {
                            MainWrapper.uidCounter.incrementAndGet();
                            openUrl(appendUri(conf.uid().get(), "uid", uid2str(uid)));
                        } else {
                            notifyUser(reader, "No action for UID: " + uid2str(uid));
                        }
                    } else {
                        // Standard NDEF URL
                        MainWrapper.urlCounter.incrementAndGet();
                        openUrl(URI.create(url));
                    }
                }
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }


    void tryToRead() {
        // This is called on the named thread of the reader.
        String n = Thread.currentThread().getName();

        // We manually open the instance
        CardTerminal t = readers.get();
        if (t == null) {
            t = MainWrapper.debug ? LoggingCardTerminal.getInstance(manager.getTerminal(n), System.err) : manager.getTerminal(n);
            readers.set(t);
        }

        Card c = null;
        try {
            // Try to get exclusive access for a second
            // c = LoggingCardTerminal.getInstance(manager.getTerminal(reader)).connect("EXCLUSIVE;*");
            c = t.connect("*");
            c.beginExclusive();
            // get UID
            APDUBIBO b = new APDUBIBO(CardBIBO.wrap(c));
            var uid = CardCommands.getUID(b);
            if (uid.isEmpty()) {
                log.info("No UID, assuming not a supported contactless reader/device");
                return;
            }
            // Type 2 > Type 4
            var url = CardCommands.getType2(b).or(() -> CardCommands.getType4(b));

            String location = null;
            if (url.isPresent())
                try {
                    // TODO: detect unknown payload. TODO: warn if smart poster
                    location = CardCommands.msg2url(url.get());
                } catch (IllegalArgumentException e) {
                    notifyUser(n, "Could not parse message etc");
                }
            onTap(n, uid.get(), location);
        } catch (Exception e) {
            // TODO: notify exclusively opened readers
            log.error("Could not connect to or read: " + e.getMessage(), e);
            notifyUser(n, "Could not read: " + SCard.getExceptionMessage(e));
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
        log.error("PC/SC Error: " + throwable.getMessage());
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

    public static URI appendUri(String uri, String key, String value) throws URISyntaxException {
        URI oldUri = new URI(uri);
        String append = key + "=" + value;
        return new URI(oldUri.getScheme(), oldUri.getAuthority(), oldUri.getPath(), oldUri.getQuery() == null ? append : oldUri.getQuery() + "&" + append, oldUri.getFragment());
    }

    public static URI appendUri(URI oldUri, String key, String value) throws URISyntaxException {
        String append = key + "=" + value;
        // NOTE: things get URLEncoded
        return new URI(oldUri.getScheme(), oldUri.getAuthority(), oldUri.getPath(), oldUri.getQuery() == null ? append : oldUri.getQuery() + "&" + append, oldUri.getFragment());
    }

    // Wait until PC/SC monitor exits
    void waitOnThread() throws InterruptedException {
        pcscMonitor.join();
    }
}