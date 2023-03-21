package pro.javacard.nfc4pc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;

public class WebHooks {
    static final Logger log = LoggerFactory.getLogger(WebHooks.class);

    final static HttpClient client = HttpClient.newHttpClient();

    static Callable<Boolean> post(URI url, Map<String, String> data, String authorization) {
        log.debug("Webhook to {}", url);
        return () -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(url)
                        .POST(HttpRequest.BodyPublishers.ofString(formdata(data)));
                if (authorization != null)
                    request.header("Authorization", authorization);
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("Failed to send webhook to {}: {}", url, response.statusCode());
                    return false;
                }
                return true;
            } catch (Throwable e) {
                log.error("Failed to send webhook", e);
                throw e;
            }
        };
    }

    private static String formdata(Map<String, String> formData) {
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, String> e : formData.entrySet()) {
            if (payload.length() > 0) {
                payload.append("&");
            }
            payload.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            payload.append("=");
            payload.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return payload.toString();
    }

    static void fireAndForget(URI uri, Map<String, String> payload) {
        try {
            ForkJoinPool.commonPool().submit(post(uri, payload, null));
        } catch (Exception e) {
            System.err.println("Could not send webhook: " + e.getMessage());
        }
    }
}
