package pro.javacard.nfc4pc;

import apdu4j.core.HexUtils;
import com.payneteasy.tlv.BerTlvParser;
import com.payneteasy.tlv.BerTlvs;
import org.junit.jupiter.api.Test;

public class MiscTest {

    @Test
    public  void testSomething() {
        byte[] v = HexUtils.hex2bin("0312D1010E55046B7962657270756E6B2E6E6574FE000000000000000000000000000000000000000000000000000000000000000000000000000000");
        BerTlvParser parser = new BerTlvParser();
        BerTlvs result = parser.parse(v);
        System.out.println(result);
    }
}
