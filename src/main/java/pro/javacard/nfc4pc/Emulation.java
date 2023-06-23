package pro.javacard.nfc4pc;

import apdu4j.core.HexUtils;
import apdu4j.pcsc.SCard;
import apdu4j.pcsc.TerminalManager;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

// Mifare ultralight emulation with ACR1252U
public class Emulation extends CLIOptions {
    static final Logger log = LoggerFactory.getLogger(Emulation.class);

    static final int code = SCard.CARD_CTL_CODE(3500);

    static void exitemu(Card c) {
        try {
            byte[] exitemu = HexUtils.hex2bin("E000004003040000");
            byte[] exit = c.transmitControlCommand(code, exitemu);
            if (!Arrays.equals(exit, HexUtils.hex2bin("E100000003040101")))
                log.warn("Exit response: " + HexUtils.bin2hex(exit));
            c.disconnect(true);
        } catch (Exception e) {
            log.warn("Failed: " + e.getMessage());
        }
    }

    static byte[] url2payload(URI uri) {
        String url = uri.toString();
        byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
        //
        byte[] payload = new byte[52];
        // 48 bytes of read-only payload of one short-style well-known NDEF record
        byte[] header = HexUtils.hex2bin("e110060F032eD1010B55");
        byte type;
        byte[] urlbytes;
        System.arraycopy(header, 0, payload, 0, header.length);
        if (url.startsWith("http://www.")) {
            type = 0x01;
            urlbytes = Arrays.copyOfRange(bytes, 11, bytes.length);
        } else if (url.startsWith("https://www.")) {
            type = 0x02;
            urlbytes = Arrays.copyOfRange(bytes, 12, bytes.length);
        } else if (url.startsWith("http://")) {
            type = 0x03;
            urlbytes = Arrays.copyOfRange(bytes, 7, bytes.length);
        } else if (url.startsWith("https://")) {
            type = 0x04;
            urlbytes = Arrays.copyOfRange(bytes, 8, bytes.length);
        } else throw new IllegalArgumentException("Invalid URL: " + url);

        payload[10] = type;
        payload[8] = (byte) (urlbytes.length + 1);

        System.arraycopy(urlbytes, 0, payload, 11, urlbytes.length);
        payload[11 + urlbytes.length] = (byte) 0xFE;
        return payload;
    }

    static void emulate(OptionSet opts) throws IOException {

        try {
            // Locate the reader. We only support ACR1252, so we can make some assumptions
            TerminalManager manager = TerminalManager.getDefault();
            final CardTerminal term;
            if (opts.has(OPT_READER))
                term = manager.getTerminal(opts.valueOf(OPT_READER));
            else {
                List<CardTerminal> terminals = manager.terminals().list();
                terminals.stream().forEach(r -> log.debug("Found reader: {}", r.getName()));
                term = terminals.stream().filter(s -> s.getName().contains("ACR1252")).findFirst().orElseThrow(() -> new RuntimeException("No ACR1252 reader found"));
                System.out.println("Using reader: " + term.getName());
            }

            // Connect direct
            final Card c = term.connect("DIRECT");

            Thread emudown = new Thread(() -> {
                System.err.println("Ctrl-C, quitting nfc4pc");
                exitemu(c);
                //sendStatistics();
            });


            // Check if reader responds with "sanity"
            byte[] getver = HexUtils.hex2bin("E000001800");
            byte[] ver = c.transmitControlCommand(code, getver);

            if (Arrays.equals(Arrays.copyOf(ver, 4), HexUtils.hex2bin("E1000000"))) {
                Runtime.getRuntime().addShutdownHook(emudown);
                String firmware = new String(Arrays.copyOfRange(ver, 5, ver.length));
                System.out.println("Firmware: " + firmware);

                // Also print it out, if asked
                if (opts.has(OPT_QR))
                    System.out.println(new QRCode().generate(opts.valueOf(OPT_EMULATE).toASCIIString()));

                // Get emulation mode
                byte[] enteremu = HexUtils.hex2bin("E000004003010000");
                byte[] emu = c.transmitControlCommand(Emulation.code, enteremu);
                log.debug("enter emulation: " + HexUtils.bin2hex(emu));

                byte[] acsheader = HexUtils.stringToBin("E0 00 00 60 1C 01 01 00 34");
                byte[] payload = url2payload(opts.valueOf(OPT_EMULATE));
                byte[] writeemu = NDEF.concatenate(acsheader, payload);
                byte[] write = c.transmitControlCommand(code, writeemu);
                log.debug("write: " + HexUtils.bin2hex(write));


                byte[] reademu = HexUtils.hex2bin("E00000600600010034");
                byte[] read = c.transmitControlCommand(code, reademu);
                log.debug("read: " + HexUtils.bin2hex(read));

                // Sleep until timeout
                long timeout = opts.has(OPT_TIMEOUT) ? (opts.valueOf(OPT_TIMEOUT) == 0 ? Long.MAX_VALUE : opts.valueOf(OPT_TIMEOUT) * 1000) : Long.MAX_VALUE;

                Thread.sleep(timeout);
                Runtime.getRuntime().removeShutdownHook(emudown);
                // TODO: track reader removal when sleeping

                // Remove emulation mode
                exitemu(c);
            } else {
                System.err.println("Invalid response from reader: " + HexUtils.bin2hex(ver));
                System.exit(1);
            }
            System.exit(0);
        } catch (CardException|InterruptedException e) {
            throw new IOException(e);
        }
    }
}
