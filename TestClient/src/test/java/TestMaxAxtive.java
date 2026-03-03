
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
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

    // Вспомогательный класс, чтобы хранить и текст отчета, и код ответа
    static class TestResult {
        int code;
        String log;
        TestResult(int code, String log) { this.code = code; this.log = log; }
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

        // 1. Теперь список хранит объекты TestResult
        List<CompletableFuture<TestResult>> futures = IntStream.rangeClosed(1, N)
            .mapToObj(id -> CompletableFuture.supplyAsync(() -> {
                Instant requestStart = Instant.now();
                try {
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    long totalTime = Duration.between(requestStart, Instant.now()).toSeconds();
                    long startTimeRel = Duration.between(testStart, requestStart).toSeconds();
                    
                    String log = String.format("Request #%d | Start on %d sec | Duration %d sec | Status: %d", 
                                                id, startTimeRel, totalTime, response.statusCode());
                    
                    return new TestResult(response.statusCode(), log);
                } catch (Exception e) {
                    return new TestResult(500, "Request #" + id + " Error: " + e.getMessage());
                }
            }))
            .collect(Collectors.toList());

        // 2. Ждем завершения
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        // 3. Выводим детальный отчет
        System.out.println("\n======= Detail report =======");
        List<Integer> statusCodes = new ArrayList<>();
        
        for (CompletableFuture<TestResult> f : futures) {
            try {
                TestResult res = f.get();
                System.out.println(res.log); // Печатаем лог
                statusCodes.add(res.code);   // Сохраняем код для ассерта
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 4. Финальный расчет
        long okCount = statusCodes.stream().filter(code -> code == 200).count();
        long errorCount = statusCodes.stream().filter(code -> code != 200).count();

        System.out.println("\n======= Final Report =======");
        System.out.println("Total: " + N + " | OK: " + okCount + " | Errors: " + errorCount);
        System.out.println("==============================\n");

        assertEquals(4, (int)okCount, "Все 4 запроса должны вернуть статус 200!");
    }
}