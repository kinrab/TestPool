
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.List;
import java.util.stream.*;
import java.io.IOException;
import java.io.File;

public class TestMaxAxtive {
    private static final int TOMCAT_STARTUP_TIMEOUT_MS = 10000;
    private static final int TOMCAT_SHUTDOWN_TIMEOUT_MS = 3000;
    private final String TOMCAT_BIN = System.getProperty("user.dir") + File.separator + ".." + File.separator + "tomcat" + File.separator + "bin";

    static class TestResult {
        int code;
        long startOffset;
        long duration;
        
        TestResult(int code, long startOffset, long duration) {
            this.code = code;
            this.startOffset = startOffset;
            this.duration = duration;
        }
    }

    @BeforeEach
    void startTomcat() throws IOException, InterruptedException {
        System.out.println("Run Tomcat from: " + TOMCAT_BIN);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();
        Thread.sleep(TOMCAT_STARTUP_TIMEOUT_MS);
    }

    @AfterEach
    void stopTomcat() throws IOException, InterruptedException {
        System.out.println("Stop Tomcat...");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "shutdown.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();
        Thread.sleep(TOMCAT_SHUTDOWN_TIMEOUT_MS);
    }

    @Test
    public void testPostgresPool() {
        int N = 4;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        String url = "http://localhost:8080/MyServletProject/MyServlet";
        
        Instant testStart = Instant.now();
        System.out.println("\n>>> Start of test... Run requests to servlet...");

        List<CompletableFuture<TestResult>> futures = IntStream.rangeClosed(1, N)
            .mapToObj(id -> CompletableFuture.supplyAsync(() -> {
                Instant requestStart = Instant.now();
                try {
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    long duration = Duration.between(requestStart, Instant.now()).toSeconds();
                    long startOffset = Duration.between(testStart, requestStart).toSeconds();
                
                    return new TestResult(response.statusCode(), startOffset, duration);
                } catch (Exception e) {
                    return new TestResult(500, -1, -1);
                }
            }))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<TestResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());

        // --- ВОЗВРАЩАЕМ ПЕЧАТЬ ОТЧЕТА ---
        System.out.println("\n======= Detail report =======");
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            System.out.printf("Request #%d | Start on %d sec | Duration %d sec | Status: %d%n", 
                               (i + 1), r.startOffset, r.duration, r.code);
        }
        System.out.println("==============================\n");
        // -------------------------------

        // Проверки (Assertions)
        long startedAtStart = results.stream().filter(r -> r.startOffset <= 1).count();
        assertEquals(4L, startedAtStart, "Все должны стартовать в начале!");

        long okCount = results.stream().filter(r -> r.code == 200).count();
        assertEquals(4L, okCount, "Все 4 должны быть OK");

        long fastRequests = results.stream().filter(r -> r.duration >= 4 && r.duration <= 6).count();
        assertEquals(2L, fastRequests, "Должно быть 2 быстрых запроса");

        long delayedRequests = results.stream().filter(r -> r.duration >= 9 && r.duration <= 11).count();
        assertEquals(2L, delayedRequests, "Должно быть 2 задержанных запроса");
        
        System.out.println(">>> Final report: OK: " + okCount + ", Errors: " + (N - okCount));
    }
}