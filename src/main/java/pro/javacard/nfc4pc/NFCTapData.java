package pro.javacard.nfc4pc;

import apdu4j.core.HexUtils;

import java.net.URI;
import java.time.Duration;

public record NFCTapData(String reader, byte[] uid, URI url, Duration readtime, Exception error) {
    public NFCTapData(String reader, Exception error) {
        this(reader, null, null, null, error);
    }

    public NFCTapData(String reader, byte[] uid, Exception error) {
        this(reader, uid, null, null, error);
    }

    public NFCTapData(String reader, byte[] uid, URI url) {
        this(reader, uid, url, null, null);
    }

    @Override
    public String toString() {
        return String.format("TapData[reader=%s, uid=%s, url=%s, readtime=%dms, error=%s]", reader, HexUtils.bin2hex(uid), url, readtime.toMillis(), error);
    }
}
