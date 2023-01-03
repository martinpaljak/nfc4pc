# NFC for PC-s!
> Makes your Desktop PC open NFC URL-s from compatible devices, just like a mobile phone does!

Small (currently Work-In-Progress, Proof-Of-Concept) Java application to solve "I want my PC to behave like a mobile phone" when working with NFC Type 4 devices (like JavaCard) and NDEF messages containing URL-s.

> https://superuser.com/questions/1756090/how-to-open-nfc-tags-on-windows-like-a-mobile-phone-woulda

## Building and using
- On Apple silicon use M1 Zulu JDK 11+
```
$ ./gradlew clean jar
$ java -jar build/libs/nfc4pc-230103.jar
```


### Scope and features:
- Java (11+) command line daemon, with an optional (if available) "system tray" UI.
- Windows, Linux, macOS (Intel, Apple)
- Scans connected PC/SC readers (apdu4j/jnasmartcardio)
- Opens URL-s of NDEF tags (Type 4, so JavaCard implementations like https://github.com/martinpaljak/NFC) with default browser, like a mobile phone
- Limited to HTTP(S) URL-s, functionally equal with https://developer.apple.com/documentation/corenfc/adding_support_for_background_tag_reading

PROBABLY:
- Configuration for browser selection
- from .jar/wrapped .exe to native package with GraalVM, if feasible

MAYBE (very MAYBE)
- support for NDEF on NXP MIFARE chips with certain readers (ACS being suspect)
- sypport for other payload types (mailto, sms, tel)
- re-implement in popular binary friendly language with native targets (Rust, Go, ... ?)
