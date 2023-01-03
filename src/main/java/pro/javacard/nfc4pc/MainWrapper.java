package pro.javacard.nfc4pc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

public class MainWrapper {
    static final URI reportURL = URI.create("https://javacard.pro/stats");
    static AtomicLong counter = new AtomicLong(0);

    // See https://stackoverflow.com/questions/43669797/run-only-in-system-tray-with-no-dock-taskbar-icon-in-java
    public static void main(String[] args) {
        System.setProperty("apple.awt.UIElement", "true");
        java.awt.Toolkit.getDefaultToolkit();

        // Trap ctrl-c
        Thread t = new Thread(() -> {
            System.out.println("Ctrl-C, quitting");
            sendStatistics().run();
        });
        Runtime.getRuntime().addShutdownHook(t);
        Main.main(args, t);
    }

    static Runnable sendStatistics() {
        return () -> {
            try {
                String payload = String.format("%s %s %s, Java %s %s %d", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"), System.getProperty("java.version"), System.getProperty("java.vendor"), counter.get());

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(reportURL)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                // Reset counter if successful
                if (response.statusCode() == 200)
                    counter.set(0);
            } catch (Throwable e) {
                System.err.println("Failed to send statistics: " + e.getMessage());
            }
        };
    }
}
