package pro.javacard.nfc4pc;

import apdu4j.core.*;
import apdu4j.pcsc.SCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;

public class CardCommands {
    static final Logger log = LoggerFactory.getLogger(CardCommands.class);

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

    // Returns the NDEF message
    static Optional<byte[]> getType2(APDUBIBO b) throws BIBOException {
        try {
            // Read initial block
            ResponseAPDU initial = b.transmit(new CommandAPDU(0xFF, 0xB0, 0x00, 3, 0x10));
            if (initial.getSW() == 0x9000 && initial.getData().length == 0x10) {
                var init = initial.getData();
                log.debug("Initial read (blocks 3, 4, 5, 6): {}", HexUtils.bin2hex(init));
                if (init[0] == (byte) 0xE1 && init[1] == 0x10) {
                    int total = (init[2] & 0xFF) * 8;
                    log.info("NDEF payload of {} bytes", total);
                    int toRead = total - init.length - 4; // So that we don't read the OTP?
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    payload.write(Arrays.copyOfRange(init, 4, init.length));
                    for (int i = 7; (i - 3) * 4 < total; i += 4) {
                        log.debug("Reading from block {} to {}, bytes {} to {}", i, i + 3, (i - 3) * 4, (i - 3) * 4 + 16);
                        log.debug("Current payload: ({} bytes) {}", payload.toByteArray().length, HexUtils.bin2hex(payload.toByteArray()));

                        var block = b.transmit(new CommandAPDU(0xFF, 0xB0, 0x00, i, 0x10));
                        var uid_bytes = block.getData();
                        if (block.getSW() == 0x9000) {
                            log.debug("Block: {}", HexUtils.bin2hex(uid_bytes));
                            if (isNull(uid_bytes)) {
                                log.debug("Empty block, not reading more");
                                break;
                            }
                            payload.write(uid_bytes);
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

    // Turn the NDEF wellknown URL record into URL string
    static String record2url(byte[] record) {
        String rest = new String(Arrays.copyOfRange(record, 1, record.length));
        switch (record[0]) {
            case 0x00:
                return rest;
            case 0x01:
                return "http://www." + rest;
            case 0x02:
                return "https://www." + rest;
            case 0x03:
                return "http://" + rest;
            case 0x04:
                return "https://" + rest;
            default:
                throw new IllegalArgumentException("Unknown URL record type: " + HexUtils.bin2hex(record));
        }
    }

    static byte[] type2_to_message(byte[] payload) {
        // Type 2: tag 03 with lentgh, so offset 2
        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    static byte[] type4_to_message(byte[] payload) {
        // Type 4: short with length, so offset 2
        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    // Extract single URL payload from message
    static String msg2url(byte[] payload) {
        log.debug("Parsing {}", HexUtils.bin2hex(payload));

        final byte[] record;

        if (payload[1] != 0x01) {
            throw new IllegalArgumentException("TNF length is not 1");
        }
        // Short record
        if ((payload[0] & 0x10) == 0x10) {
            if (payload[3] != 0x55)
                throw new IllegalArgumentException("Unsupported TNF");
            int len = payload[2] & 0xFF;
            record = Arrays.copyOfRange(payload, 4, 4 + len);
        } else {
            if (payload[6] != 0x55)
                throw new IllegalArgumentException("Unsupported TNF");
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.order(ByteOrder.BIG_ENDIAN);
            int len = buffer.getInt(2);
            record = Arrays.copyOfRange(payload, 7, 7 + len);
        }
        return record2url(record);
    }

    static Optional<byte[]> getType4(APDUBIBO bibo) {
        log.debug("Trying to read Type 4 NDEF tag");
        try {
            ResponseAPDU select = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, NFC4PC.NDEF_AID, 256));
            if (select.getSW() == 0x9000) {
                ResponseAPDU cap = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x00, 0x0C, HexUtils.hex2bin("e103")));
                if (cap.getSW() == 0x9000) {
                    // Capabilities
                    ResponseAPDU read = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, 0x0F));

                    int maxReadSize = NFC4PC.getShort(read.getData(), (short) 3);
                    int payloadSize = NFC4PC.getShort(read.getData(), (short) 11);

                    ResponseAPDU selectDATA = bibo.transceive(new CommandAPDU(0x00, 0xA4, 0x00, 0x0C, HexUtils.hex2bin("e104")));
                    if (selectDATA.getSW() == 0x9000) {
                        ResponseAPDU len = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x00, 0x02));
                        int reportedLen = NFC4PC.getShort(len.getData(), (short) 0);
                        if ((reportedLen + 2) != payloadSize) {
                            log.error("Warning: payload length mismatch");
                        }
                        if (len.getSW() == 0x9000) {
                            final byte[] payload;
                            if (reportedLen > maxReadSize) { // XXX: assumes that not that big
                                byte[] chunk1 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, 0x02, maxReadSize)).getData();
                                byte[] chunk2 = bibo.transceive(new CommandAPDU(0x00, 0xb0, 0x00, maxReadSize + 2, reportedLen - maxReadSize)).getData();
                                payload = NFC4PC.concatenate(chunk1, chunk2);
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
            if (err.isPresent()) {
                log.error("Failed to read type 4: {}", err.get());
            }
        }
        return Optional.empty();
    }
}
