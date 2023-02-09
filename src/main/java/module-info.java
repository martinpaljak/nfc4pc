module pro.javacard.nfc4pc {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.swingEmpty;
    requires apdu4j.pcsc;
    requires apdu4j.core;
    requires java.desktop;
    requires java.net.http;
    requires org.slf4j;
    requires com.dustinredmond.fxtrayicon;
    requires com.sun.jna;
    requires jopt.simple;
    requires ber.tlv;

    exports pro.javacard.nfc4pc;
}