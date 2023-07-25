package pro.javacard.nfc4pc;

import apdu4j.core.*;
import apdu4j.pcsc.SCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

public class NDEF {
    static final Logger log = LoggerFactory.getLogger(NDEF.class);
    // D2760000850101
    static final byte[] NDEF_AID = new byte[]{(byte) 0xD2, (byte) 0x76, (byte) 0x00, (byte) 0x00, (byte) 0x85, (byte) 0x01, (byte) 0x01};

    // Reads the UID, if available (might not be supported by reader or might be unsupported chip/technology)
    static Optional<byte[]> getUID(APDUBIBO b) throws BIBOException {
        var uid = b.transmit(new CommandAPDU(0xFF, 0xCA, 0x00, 0x00, 256));

        if (uid.getSW() == 0x9000 && Arrays.asList(4, 7, 10).contains(uid.getData().length)) {
            var uid_bytes = uid.getData();
            log.info("UID: {}", HexUtils.bin2hex(uid_bytes));
            return Optional.of(uid_bytes);
        }
        return Optional.empty();
    }

    static boolean isNull(byte[] b) {
        for (Byte v : b)
            if (v != 0)
                return false;
        return true;
    }

    // Returns the NDEF message, if any
    static Optional<byte[]> getType2(APDUBIBO b) throws BIBOException {
        try {
            // Read capability container (4th block)
            ResponseAPDU initial = b.transmit(new CommandAPDU(0xFF, 0xB0, 0x00, 3, 0x04));
            // Cloud 3700F: returns 4 bytes even if asked for 16. OK 5022 returns 16 bytes even if asked for 4.
            if (initial.getSW() == 0x9000 && initial.getData().length >= 4) {
                var init = initial.getData();
                log.debug("Capability container: {}", HexUtils.bin2hex(init));
                if (init[0] == (byte) 0xE1 && init[1] == 0x10) {
                    int total = (init[2] & 0xFF) * 8;
                    log.info("NDEF payload of {} bytes", total);
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();

                    for (int blocknum = 4; payload.toByteArray().length < total; ) {
                        log.debug("Reading block {}", blocknum);
                        log.debug("Current payload: ({} bytes) {}", payload.toByteArray().length, HexUtils.bin2hex(payload.toByteArray()));

                        var block = b.transmit(new CommandAPDU(0xFF, 0xB0, 0x00, blocknum, 4));
                        var bytes = block.getData();
                        if (block.getSW() == 0x9000) {
                            log.debug("Block: {}", HexUtils.bin2hex(bytes));
                            if (isNull(bytes)) {
                                log.debug("Empty block, not reading more");
                                break;
                            }
                            payload.write(bytes);
                            blocknum += bytes.length / 4;
                        } else {
                            log.warn("Read returned {}", HexUtils.bin2hex(block.getBytes()));
                            return Optional.empty();
                        }
                    }
                    return Optional.of(type2_to_message(payload.toByteArray()));
                } else {
                    log.warn("Invalid capability block: {}", HexUtils.bin2hex(init));
                }
            } else {
                log.info("Failed to read initial block: {}", HexUtils.bin2hex(initial.getBytes()));
            }
        } catch (IOException e) {
            log.error("Could not read: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    // Turn the NDEF well-known URL record into URL string
    static String record2url(byte[] record) {
        String rest = new String(Arrays.copyOfRange(record, 1, record.length));
        return switch (record[0]) {
            case 0x00 -> rest;
            case 0x01 -> "http://www." + rest;
            case 0x02 -> "https://www." + rest;
            case 0x03 -> "http://" + rest;
            case 0x04 -> "https://" + rest;
            default -> throw new IllegalArgumentException("Unsupported URL record type: " + HexUtils.bin2hex(record));
        };
    }

    static byte[] type2_to_message(byte[] payload) {
        log.debug("Parsing {}", HexUtils.bin2hex(payload));
        int pos = 0;
        if (payload[pos] == 0x01)
            pos += payload[pos + 1] + 1;
        if (payload[pos] == 0x02)
            pos += payload[pos + 1] + 1;
        byte[] msg = Arrays.copyOfRange(payload, pos + 2, pos + 2 + payload[pos + 1]);
        log.debug("Message: {}", HexUtils.bin2hex(msg));
        return msg;
    }

    static byte[] type4_to_message(byte[] payload) {
        // Type 4: short with length, so offset 2
        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    // Extract single URL payload from message
    static String msg2url(byte[] payload) {
        log.debug("Parsing {}", HexUtils.bin2hex(payload));

        final byte[] record;

        // Give a helpful message when using smart posters
        if (payload[1] == 0x02 && payload[3] == 0x53 && payload[4] == 0x70)
            throw new IllegalArgumentException("Smart Poster would not be supported by iPhone. Ignoring");

        if (payload[1] != 0x01) {
            throw new IllegalArgumentException("Unsupported NDEF message: TNF length is not 1");
        }
        // Short record
        if ((payload[0] & 0x10) == 0x10) {
            if (payload[3] != 0x55)
                throw new IllegalArgumentException("Unsupported TNF");
            int len = payload[2] & 0xFF;
            record = Arrays.copyOfRange(payload, 4, 4 + len);
        } else if ((payload[0] & 0x10) == 0x00) {
            if (payload[6] != 0x55)
                throw new IllegalArgumentException("Unsupported TNF");
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            int len = buffer.getInt(2);
            record = Arrays.copyOfRange(payload, 7, 7 + len);
        } else throw new IllegalArgumentException("Invalid SR bit");
        return record2url(record);
    }

    static Optional<byte[]> getType4(APDUBIBO bibo) {
        log.debug("Trying to read Type 4 NDEF tag");
        try {
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
                        ResponseAPDU len = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, 0x02));
                        int reportedLen = getShort(len.getData(), (short) 0);
                        if ((reportedLen + 2) != payloadSize) {
                            log.error("Warning: payload length mismatch");
                        }
                        if (len.getSW() == 0x9000) {
                            final byte[] payload;
                            if (reportedLen > maxReadSize) { // XXX: assumes that not that big
                                byte[] chunk1 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x02, maxReadSize)).getData();
                                byte[] chunk2 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, maxReadSize + 2, reportedLen - maxReadSize)).getData();
                                payload = concatenate(chunk1, chunk2);
                            } else {
                                payload = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x02, reportedLen)).getData();
                            }
                            log.info("Payload: " + HexUtils.bin2hex(payload));
                            return Optional.of(payload);
                        }
                    }
                }
            } else {
                log.debug("SELECT NDEF was not 0x9000");
            }
        } catch (BIBOException e) {
            Optional<String> err = SCard.getPCSCError(e);
            err.ifPresent(s -> log.error("Failed to read type 4: {}", s));
        }
        return Optional.empty();
    }

    public static byte[] concatenate(byte[]... args) {
        int length = 0;
        int pos = 0;
        int var4 = args.length;

        int var5;
        for (var5 = 0; var5 < var4; ++var5) {
            byte[] arg = args[var5];
            length += arg.length;
        }

        byte[] result = new byte[length];
        var5 = args.length;

        for (int var10 = 0; var10 < var5; ++var10) {
            byte[] arg = args[var10];
            System.arraycopy(arg, 0, result, pos, arg.length);
            pos += arg.length;
        }

        return result;
    }

    public static short getShort(byte[] bArray, short bOff) throws ArrayIndexOutOfBoundsException, NullPointerException {
        return (short) (((short) bArray[bOff] << 8) + ((short) bArray[bOff + 1] & 255));
    }
}
