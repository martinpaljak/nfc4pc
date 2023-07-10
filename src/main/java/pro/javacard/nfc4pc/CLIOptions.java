package pro.javacard.nfc4pc;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public abstract class CLIOptions {
    static final OptionParser parser = new OptionParser();
    protected static OptionSpec<Void> OPT_VERSION = parser.acceptsAll(List.of("V", "version"), "Show information about the program");
    protected static OptionSpec<Void> OPT_HELP = parser.acceptsAll(List.of("h", "?", "help"), "Shows this help").forHelp();
    protected static OptionSpec<Void> OPT_DEBUG = parser.acceptsAll(List.of("d", "debug"), "Show debugging logs");
    protected static OptionSpec<Void> OPT_DESKTOP = parser.acceptsAll(List.of("desktop"), "Run as system tray item");
    protected static OptionSpec<Void> OPT_CONTINUE = parser.acceptsAll(List.of("c", "continue"), "Read continuously");
    protected static OptionSpec<Void> OPT_CLEAR = parser.acceptsAll(List.of("C", "clear"), "Clear screen and read continuously");
    protected static OptionSpec<URI> OPT_UID_URL = parser.acceptsAll(List.of("uid-url"), "Launch UID-s at given URL").withRequiredArg().ofType(URI.class);
    protected static OptionSpec<URI> OPT_WEBHOOK = parser.acceptsAll(List.of("webhook"), "Post data to webhook").withRequiredArg().ofType(URI.class);
    protected static OptionSpec<String> OPT_AUTHORIZATION = parser.acceptsAll(List.of("authorization"), "Authorization header for webhook").availableIf(OPT_WEBHOOK).withRequiredArg();
    protected static OptionSpec<URI> OPT_META_URL = parser.acceptsAll(List.of("meta-url"), "Launch all tags at given URL").availableUnless(OPT_WEBHOOK, OPT_UID_URL).withRequiredArg().ofType(URI.class);
    protected static OptionSpec<Void> OPT_HEADLESS = parser.acceptsAll(List.of("headless"), "Run in headless (webhook-only) mode").availableIf(OPT_WEBHOOK).availableUnless(OPT_DESKTOP);
    protected static OptionSpec<String> OPT_BROWSER = parser.acceptsAll(List.of("browser"), "Execute browser").availableUnless(OPT_WEBHOOK).withOptionalArg().describedAs("path");
    protected static OptionSpec<URI> OPT_QR = parser.acceptsAll(List.of("qrcode"), "Show QR code").availableUnless(OPT_DESKTOP, OPT_HEADLESS).withOptionalArg().ofType(URI.class);
    protected static OptionSpec<URI> OPT_GO = parser.acceptsAll(List.of("go"), "Go to URL").availableUnless(OPT_DESKTOP, OPT_HEADLESS).withRequiredArg().ofType(URI.class);
    protected static OptionSpec<Integer> OPT_TIMEOUT = parser.acceptsAll(List.of("timeout"), "Time out after X seconds").availableUnless(OPT_DESKTOP, OPT_HEADLESS).withRequiredArg().ofType(Integer.class).defaultsTo(30);
    protected static OptionSpec<URI> OPT_EMULATE = parser.acceptsAll(List.of("emulate"), "Emulate a tag with URL").withRequiredArg().ofType(URI.class);
    protected static OptionSpec<String> OPT_READER = parser.acceptsAll(List.of("reader"), "Reader to use for tag emulation").availableIf(OPT_EMULATE).withRequiredArg();


    protected static OptionSet parseArguments(String[] argv) throws IOException {
        OptionSet args = null;

        // Parse arguments
        try {
            args = parser.parse(argv);
        } catch (OptionException e) {
            parser.printHelpOn(System.err);
            System.err.println();
            if (e.getCause() != null) {
                System.err.println(e.getMessage() + ": " + e.getCause().getMessage());
            } else {
                System.err.println(e.getMessage());
            }
            System.exit(1);
        }

        if (args.nonOptionArguments().size() > 0) {
            System.err.println();
            System.err.println("Invalid non-option arguments: " + args.nonOptionArguments().stream().map(Object::toString).collect(Collectors.joining(" ")));
            System.exit(1);
        }

        if (args.has(OPT_HELP)) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        if (args.has(OPT_VERSION)) {
            if (args.has(OPT_DEBUG)) {
                if (System.getProperty("os.name").equalsIgnoreCase("Mac OS X")) {
                    ProcessBuilder pb = new ProcessBuilder(List.of("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"));
                    Process process = pb.start();
                    String result = new String(process.getInputStream().readAllBytes()).trim();
                    System.out.printf("# Running Java %s (%s) from %s on %s %s (%s)%n", System.getProperty("java.version"), System.getProperty("os.arch"), System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.version"), result);
                } else
                    System.out.printf("# Running Java %s (%s) from %s on %s %s%n", System.getProperty("java.version"), System.getProperty("os.arch"), System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.version"));
            }
            System.out.println("NFC4PC version " + CLIOptions.class.getPackage().getImplementationVersion());
            System.exit(0);
        }

        return args;
    }
}
