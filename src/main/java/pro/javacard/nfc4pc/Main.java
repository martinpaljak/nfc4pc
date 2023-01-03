package pro.javacard.nfc4pc;

import apdu4j.core.APDUBIBO;
import apdu4j.core.CommandAPDU;
import apdu4j.core.HexUtils;
import apdu4j.core.ResponseAPDU;
import apdu4j.pcsc.*;
import com.dustinredmond.fxtrayicon.FXTrayIcon;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
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

    private final TerminalManager manager = TerminalManager.getDefault();
    private final Thread pcscMonitor = new Thread(new HandyTerminalsMonitor(manager, this));

    // This executor holds the reader and executes tasks on the thread owning the reader, rejecting tasks if one is active
    private static final ExecutorService readerThread = new ThreadPoolExecutor(1, 3, Long.MAX_VALUE, TimeUnit.NANOSECONDS, new SynchronousQueue<>());

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

    public static void main(String[] args, Thread theHook) {
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
                readerThread.submit(() -> tryToRead(n));
            }
        }
    }

    void tryToRead(String reader) {
        Card c = null;
        try {
            // Try to get exclusive access for a second
            c = manager.getTerminal(reader).connect("EXCLUSIVE;*");
            Optional<String> url = getURL(new APDUBIBO(CardBIBO.wrap(c)));
            log.info("Read URL: {}", url);
            url.ifPresent(this::onUrl);
        } catch (Exception e) {
            log.error("Could not connect to or read from " + reader, e);
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

    Optional<String> getURL(APDUBIBO bibo) {
        ResponseAPDU select = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, NDEF_AID, 256));
        if (select.getSW() == 0x9000) {
            ResponseAPDU cap = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x00, 0x0C, HexUtils.hex2bin("e103")));
            if (cap.getSW() == 0x9000) {
                // Capabilities
                ResponseAPDU read = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, 0x0F));

                int maxReadSize = getShort(read.getData(), (short) 3);
                int payloadSize = getShort(read.getData(), (short) 11);

                ResponseAPDU selectDATA = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x00, 0x0C, HexUtils.hex2bin("e104")));
                if (selectDATA.getSW() == 0x9000) {
                    final byte[] payload;
                    if (payloadSize > maxReadSize) { // XXX: assumes that not that big
                        byte[] chunk1 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, maxReadSize)).getData();
                        byte[] chunk2 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, maxReadSize, payloadSize - maxReadSize)).getData();
                        payload = concatenate(chunk1, chunk2);
                    } else {
                        payload = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, 256)).getData();
                    }
                    // FIXME: https only
                    log.info("Payload: " + HexUtils.bin2hex(payload));
                    final String urlString;
                    if ((payload[2] & 0x10) == 0x10) {
                        urlString = "https://" + new String(Arrays.copyOfRange(payload, 7, payload.length));
                    } else {
                        urlString = "https://" + new String(Arrays.copyOfRange(payload, 10, payload.length));
                    }
                    return Optional.of(urlString);
                }
            }
        } else {
            log.debug("SELECT NDEF was not 0x9000");
        }
        return Optional.empty();
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

}