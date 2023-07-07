package pro.javacard.nfc4pc;

import apdu4j.core.HexUtils;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class NFC4PC extends CLIOptions implements TapProcessor {
    static final Logger log = LoggerFactory.getLogger(NFC4PC.class);

    final static int DEFAULT_TIMEOUT = 30;

    final static String ANSI_CLEAR_SCREEN = "\033[H\033[2J";
    boolean daemon;
    ScheduledThreadPoolExecutor idle = new ScheduledThreadPoolExecutor(1);

    final ScheduledFuture<?> idler;

    URI webhook;
    final OptionSet opts;

    final Thread shutdownHook;

    public NFC4PC(OptionSet opts, Thread shutdownHook) {
        this.opts = opts;

        this.shutdownHook = shutdownHook;
        // Desktop mode, continuous mode or headless daemon
        daemon = opts.has(OPT_DESKTOP) || opts.has(OPT_CONTINUE) || opts.has(OPT_HEADLESS);
        log.info("Daemon mode: {}", daemon);
        webhook = opts.valueOf(OPT_WEBHOOK);
        log.info("Webhook: {}", webhook);


        // Set idle quit for non-daemon mode
        if (!daemon) {
            final long secs = opts.has(OPT_TIMEOUT) ? opts.valueOf(OPT_TIMEOUT) : DEFAULT_TIMEOUT;

            log.info("Timeout secs: {}", secs);

            // by default there is timeout, unless run with -t 0
            if (secs > 0) {
                idler = idle.schedule(() ->{
                    System.err.printf("Timeout, no tap within %d seconds!%n", secs);
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    System.exit(2);
                }, secs, TimeUnit.SECONDS);
            } else {
                idler = null;
            }
        } else
            idler = null;
    }

    @Override
    public void onNFCTap(NFCTapData data) {
        log.info("TAP: {}", data);

        // Cancel idler!
        if (idler != null)
            idler.cancel(true);

        // Clear screen if needed
        if (opts.has(OPT_CLEAR) && MainWrapper.tapCounter.get() > 0)
            System.out.print(ANSI_CLEAR_SCREEN);
        MainWrapper.tapCounter.incrementAndGet();

        if (opts.has(OPT_CONTINUE))
            System.out.printf("# Tap #%d (%s)%n", MainWrapper.tapCounter.get(), data.reader());

        try {
            if (data.error() != null) {
                if (console()) {
                    System.err.println("WARNING: " + data.error().getMessage());
                } else {
                    log.error(data.error().getMessage(), data.error());
                    // FIXME: log or show notification
                }
            }
            if (data.uid() != null) {
                if (opts.has(OPT_WEBHOOK)) {
                    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
                    payload.put("uid", uid2str(data.uid()));
                    if (data.url() != null)
                        payload.put("url", data.url().toString());
                    MainWrapper.webhookCounter.incrementAndGet();
                    try {
                        if (!WebHooks.post(opts.valueOf(OPT_WEBHOOK), payload, opts.valueOf(OPT_AUTHORIZATION)).call()) {
                            log.error("Failed to post webhook to " + opts.valueOf(OPT_WEBHOOK));
                        }
                    } catch (Exception e) {
                        log.error("Failed to post webhook to " + opts.valueOf(OPT_WEBHOOK) + ": " + e.getMessage(), e);
                    }

                } else {
                    if (data.url() == null && !opts.has(OPT_UID_URL) && !opts.has(OPT_META_URL)) {
                        log.info("Ignoring tag uid:{} without usable payload", HexUtils.bin2hex(data.uid()));
                        return;
                    }
                    URI uri = transform(data, opts);
                    if (console()) {
                        if (opts.has(OPT_BROWSER)) {
                            openBrowser(uri);
                        } else {
                            if (opts.has(OPT_QR)) {
                                System.out.println(new QRCode().generate(uri));
                            }
                            System.out.println(uri);
                        }
                    } else {
                        openBrowser(uri);
                    }
                }
            }
        } catch (URISyntaxException e) {
            log.error("Could not transform payload: " + e.getMessage(), e);
        }

        if (!daemon) {
            log.debug("Done, exiting");
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            System.exit(0);
        }
    }

    static URI transform(NFCTapData data, OptionSet opts) throws URISyntaxException {
        // Meta, if configured
        URI target;
        if (opts.has(OPT_META_URL)) {
            target = appendUri(opts.valueOf(OPT_META_URL), "uid", uid2str(data.uid()));
            if (data.url() != null) {
                target = appendUri(target, "url", URLEncoder.encode(data.url().toASCIIString(), StandardCharsets.UTF_8));
            }
        } else if (data.url() == null && opts.has(OPT_UID_URL)) {
            // or UID, if url is empty
            target = appendUri(opts.valueOf(OPT_UID_URL), "uid", uid2str(data.uid()));
        } else {
            // or actual ndef url
            target = data.url();
        }
        return target;
    }

    public static URI appendUri(URI oldUri, String key, String value) throws URISyntaxException {
        String append = key + "=" + value;
        // NOTE: things get URLEncoded
        return new URI(oldUri.getScheme(), oldUri.getAuthority(), oldUri.getPath(), oldUri.getQuery() == null ? append : oldUri.getQuery() + "&" + append, oldUri.getFragment());
    }

    static String uid2str(byte[] uid) {
        return HexUtils.bin2hex(uid).toLowerCase();
    }

    public static Process exec(String... args) throws IOException {
        log.info("Executing {}", Arrays.stream(args).toList());
        if (System.console() != null)
            // Inherit IO so that commands can print things
            return new ProcessBuilder().inheritIO().command(args).start();
        else
            // No need for IO if we don't have a console
            return new ProcessBuilder().command(args).start();
    }

    public static void openBrowser(URI url, OptionSet opts) {
        log.info("Opening browser for {}", url);
        try {
            String browser_env = System.getenv("BROWSER");
            if (opts.hasArgument(OPT_BROWSER)) {
                log.info("Launching browser: " + opts.valueOf(OPT_BROWSER) + " " + url.toString());
                exec(opts.valueOf(OPT_BROWSER), url.toString());
            } else if (browser_env != null) {
                log.info("Launching browser: " + browser_env + " " + url.toString());
                exec(browser_env, url.toString());
            } else {
                Desktop desktop = Desktop.getDesktop();
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(url);
                } else {
                    throw new IllegalStateException("No browser config and desktop action not available");
                }
            }
        } catch (IOException e) {
            log.error("Could not start browser: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void openBrowser(URI url) {
        openBrowser(url, opts);
    }

    boolean console() {
        return !(opts.has(OPT_DESKTOP) || opts.has(OPT_HEADLESS));
    }
}
