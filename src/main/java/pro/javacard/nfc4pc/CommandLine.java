package pro.javacard.nfc4pc;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class CommandLine {
    static OptionParser parser = new OptionParser();
    protected static OptionSpec<Void> OPT_VERSION = parser.acceptsAll(Arrays.asList("V", "version"), "Show information about the program");
    protected static OptionSpec<Void> OPT_HELP = parser.acceptsAll(Arrays.asList("h", "?", "help"), "Shows this help").forHelp();
    protected static OptionSpec<String> OPT_UID_URL = parser.acceptsAll(Arrays.asList("U", "uid-url"), "Launch UID-s at given URL").withRequiredArg();


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
