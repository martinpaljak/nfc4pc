package pro.javacard.nfc4pc;

import apdu4j.core.APDUBIBO;
import apdu4j.core.BIBOException;
import apdu4j.pcsc.*;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class NFCReader implements PCSCMonitor {
    static final Logger log = LoggerFactory.getLogger(NFCReader.class);

    // Let's have a thread per reader and a monitoring thread, in addition to the UI thread. Many threads, yay!
    // This is indeed too many threads, but it is also fun. At some point have an event thread and a worker thread and an outgoing read queue.
    private final TerminalManager manager = TerminalManager.getDefault();
    private final Thread pcscMonitor = new Thread(new HandyTerminalsMonitor(manager, this));

    // CardTerminal instance is kept per thread.
    private final ConcurrentHashMap<String, ExecutorService> readerThreads = new ConcurrentHashMap<>();
    static ThreadLocal<CardTerminal> readers = ThreadLocal.withInitial(() -> null);

    private final TapProcessor processor;

    public NFCReader(TapProcessor processor) {
        this.processor = processor;
        // start monitor thread
        pcscMonitor.setDaemon(true);
        pcscMonitor.setName("PC/SC monitor");
        pcscMonitor.start();
    }

    // This is a fun exercise, we have a thread per reader and use the thread name for logging as well as reader access.
    private void onReaderThread(String name, Runnable r) {
        readerThreads.computeIfAbsent(name, (n) -> Executors.newSingleThreadExecutor(new NamedReaderThreadFactory(n))).submit(r);
    }

    public void waitForever() throws InterruptedException {
        pcscMonitor.join();
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

    private final Map<String, Boolean> readerStates = new HashMap<>();

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

                // Try to read
                onReaderThread(n, this::tryToRead);
            }
            // Store state of _this_ notification
            readerStates.put(n, newStates.get(n));
        }
    }


    private void tryToRead() {
        // This is called on the named thread of the reader.
        String n = Thread.currentThread().getName();

        // We manually open the instance
        CardTerminal t = readers.get();
        if (t == null) {
            t = log.isDebugEnabled() ? LoggingCardTerminal.getInstance(manager.getTerminal(n), System.err) : manager.getTerminal(n);
            readers.set(t);
        }

        Card c = null;
        try {
            // Try to get exclusive access for a second
            c = t.connect("*");
            c.beginExclusive(); // Use locking, as this is short read
            // get UID
            APDUBIBO b = new APDUBIBO(CardBIBO.wrap(c));

            long start = System.currentTimeMillis();
            var uid = NDEF.getUID(b);
            if (uid.isEmpty()) {
                log.info("No UID, assuming not a supported contactless reader/device");
                processor.onNFCTap(new NFCTapData(n, new RuntimeException("No UID, unsupported reader and/or tag")));
                return;
            }
            // Type 2 > Type 4
            var url = NDEF.getType2(b).or(() -> NDEF.getType4(b));
            Duration readtime = Duration.ofMillis(System.currentTimeMillis() - start);


            String location = null;
            if (url.isPresent()) {
                try {
                    // TODO: detect unknown payload. TODO: warn if smart poster
                    location = NDEF.msg2url(url.get());
                    processor.onNFCTap(new NFCTapData(n, uid.get(), URI.create(location), readtime, null));
                } catch (IllegalArgumentException e) {
                    processor.onNFCTap(new NFCTapData(n, uid.get(), e));
                    return;
                    //notifyUser(n, "Could not parse message etc");
                }
            } else {
                processor.onNFCTap(new NFCTapData(n, uid.get(), null, readtime, null));
            }
        } catch (BIBOException e) {
            // TODO: notify exclusively opened readers ?
            log.error("Could not connect to or read: " + e.getMessage(), e);
            processor.onNFCTap(new NFCTapData(n, new IOException("Could not read: " + SCard.getExceptionMessage(e))));
        } catch (Exception e) {
            log.error("Could not connect to or read: " + e.getMessage(), e);
            processor.onNFCTap(new NFCTapData(n, new IOException("Could not read: " + SCard.getExceptionMessage(e))));
        } finally {
            if (c != null)
                try {
                    c.disconnect(true);
                } catch (CardException e) {
                    log.warn("Could not disconnect: " + e.getMessage(), e);
                }
        }
    }


    @Override
    public void readerListErrored(Throwable throwable) {
        log.error("PC/SC Error: " + throwable.getMessage());
    }
}
