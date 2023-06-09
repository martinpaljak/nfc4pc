package pro.javacard.nfc4pc;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QRCode {

    public static final String BLACK = "\033[40m  \033[0m";
    public static final String WHITE = "\033[47m  \033[0m";

    // Some folks on web use a "compressed" matrix with ansi blocks, but that looked uglier on my terminal
    public String generate(String content)  {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0);
            return matrix.toString(BLACK, WHITE);
        } catch (WriterException e) {
            throw new RuntimeException(e);
        }
    }
}
