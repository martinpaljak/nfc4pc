package pro.javacard.nfc4pc;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public abstract class CommandLine {
    static final OptionParser parser = new OptionParser();
    protected static OptionSpec<Void> OPT_VERSION = parser.acceptsAll(Arrays.asList("V", "version"), "Show information about the program");
    protected static OptionSpec<Void> OPT_HELP = parser.acceptsAll(Arrays.asList("h", "?", "help"), "Shows this help").forHelp();
    protected static OptionSpec<Void> OPT_DEBUG = parser.acceptsAll(Arrays.asList("d", "debug"), "Show debugging logs");

    protected static OptionSpec<Void> OPT_DESKTOP = parser.acceptsAll(Arrays.asList("desktop"), "Run as system tray item");

    protected static OptionSpec<URI> OPT_UID_URL = parser.acceptsAll(Arrays.asList("U", "uid-url"), "Launch UID-s at given URL").withRequiredArg().ofType(URI.class);
    protected static OptionSpec<URI> OPT_WEBHOOK = parser.acceptsAll(Arrays.asList("w", "webhook"), "Post data to webhook").withRequiredArg().ofType(URI.class);
    protected static OptionSpec<URI> OPT_META_URL = parser.acceptsAll(Arrays.asList("M", "meta-url"), "Launch URL-s at given URL").availableUnless(OPT_WEBHOOK, OPT_UID_URL).withRequiredArg().ofType(URI.class);
    protected static OptionSpec<Void> OPT_NO_GUI = parser.acceptsAll(List.of("no-gui"), "Run without GUI");

    protected static OptionSpec<Void> OPT_HEADLESS = parser.acceptsAll(List.of("headless"), "Run in headless (webhook-only) mode").availableIf(OPT_WEBHOOK).availableUnless(OPT_DEBUG);
    protected static OptionSpec<String> OPT_AUTHORIZATION = parser.acceptsAll(List.of("auhtorization"), "Authorization header").availableIf(OPT_WEBHOOK).withRequiredArg();


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
            System.err.println("Invalid non-option arguments: " + args.nonOptionArguments().stream().map(e -> e.toString()).collect(Collectors.joining(" ")));
            System.exit(1);
        }

        if (args.has(OPT_HELP)) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        return args;
    }
}
