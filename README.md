# NFC for PC-s!
> Makes your Desktop PC open NFC URL-s from compatible devices, just like a mobile phone does!

Small (currently Work-In-Progress, Proof-Of-Concept) Java application to solve "I want my PC to behave like a mobile phone" when working with NFC Type 4 devices (like JavaCard) and NDEF messages containing URL-s.

> https://superuser.com/questions/1756090/how-to-open-nfc-tags-on-windows-like-a-mobile-phone-woulda

### See it in action!
A crappy video of a quick hack! https://mrtn.ee/tmp/nfc4pc.mp4

## Building and using
- On Apple silicon use M1 Zulu JDK 11+
```
$ ./gradlew clean jar
$ java -jar build/libs/nfc4pc-230103.jar
```

## Nota Bene
NFC4PC does not (at least now) intend to become an all-in-one NFC toolkit for PC-s, but be the missing "no-software"-software component. NFC4PC was created as the missing link between cloud based web applications (that don't need more than a modern web browser) and physical NFC tags: when a (compatible) tag meets a (compatible) PC/SC reader, a website opens, just like on a mobile phone. The rest is up to the cloud application.

If/when Microsoft or Apple or Google or Mozilla will build similar capabilities into their desktop/browsers, this software will cease to exist. Until that happens, this little piece of software shall live on and also grow some extra capabilities that the community sees value in.


### Scope and features:
- Java (11+) command line daemon, with a "system tray" UI (if available)
- Windows, Linux, macOS (Intel, Apple)
- Scans connected PC/SC readers (apdu4j/jnasmartcardio)
- Opens URL-s of NDEF tags (Type 4, so JavaCard implementations like https://github.com/martinpaljak/NFC) with the default browser
- Limited to HTTP(S) URL-s, functionally equal with https://developer.apple.com/documentation/corenfc/adding_support_for_background_tag_reading
- Dedicated UID URL - https://github.com/martinpaljak/NFC4PC/issues/1

PROBABLY:
- GUI configuration for browser selection and other tunables
- from .jar/wrapped .exe to native package with GraalVM, if feasible

MAYBE (very MAYBE)
- support for NDEF on NXP MIFARE/NTAG chips with certain readers (ACS being suspect)
- sypport for other payload types (mailto, sms, tel)
- re-implement in popular binary friendly language with native targets (Rust, Go, ... ?)
