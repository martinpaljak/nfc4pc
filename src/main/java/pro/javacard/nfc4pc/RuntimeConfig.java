package pro.javacard.nfc4pc;

import java.net.URI;
import java.util.Optional;

public class RuntimeConfig {
    private URI uidurl;
    private URI metaurl;
    private URI webhookurl;
    private String authorization;

    public RuntimeConfig(URI uidurl, URI metaurl, URI webhookurl, String authorization) {
        this.uidurl = uidurl;
        this.metaurl = metaurl;
        this.webhookurl = webhookurl;
        this.authorization = authorization;
    }

    Optional<URI> uid() {
        return Optional.ofNullable(uidurl);
    }

    Optional<URI> meta() {
        return Optional.ofNullable(metaurl);
    }

    Optional<URI> webhook() {
        return Optional.ofNullable(webhookurl);
    }

    Optional<String> auth() {
        return Optional.ofNullable(authorization);
    }
}
