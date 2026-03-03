
import io.qameta.allure.Allure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.qameta.allure.Description;

import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.List;
import java.util.stream.*;
import java.io.IOException;
import java.io.File;

public class TestMaxAxtive 
{
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
    @Feature("Postgres Connection Pool")
    @Story("MaxActive limit check")
    @Description("Проверка очереди Tomcat: запроса сразу, и в ожидании.")
    
   public void testPostgresPool() 
   {
        int N = 4;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        String url = "http://localhost:8080/MyServletProject/MyServlet";

        Instant testStart = Instant.now();
        System.out.println("\n>>> Start of test... Run requests to servlet...");

        // ШАГ 1. PЗАПУСКАЕМ N потоков с HTTP-запросом GET:
        List<TestResult> results = Allure.step("1. Отправка " + N + " параллельных запросов к сервлету", () -> {

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

            // Ждем здесь же, внутри шага, чтобы Allure замерил полное время выполнения всех потоков
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // Возвращаем результат из шага наружу в переменную results
            return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        });

        // ШАГ 2: Логирование в отчет Allure
        Allure.step("Проверка результатов (Assertions)", () -> 
        {  
            long startedAtStart = results.stream().filter(r -> r.startOffset <= 1).count();
            long okCount = results.stream().filter(r -> r.code == 200).count();
            long fastRequests = results.stream().filter(r -> r.duration >= 4 && r.duration <= 6).count();
            long delayedRequests = results.stream().filter(r -> r.duration >= 9 && r.duration <= 11).count();

            // Каждая проверка отдельный вложенный шаг:
            Allure.step("Проверка: Все потоки стартовали одновременно", () -> 
                assertEquals(4L, startedAtStart, "Все должны стартовать в начале!")
            );

            Allure.step("Проверка: Все 4 ответа имеют статус 200", () -> 
                assertEquals(4L, okCount, "Все 4 должны быть OK")
            );

            Allure.step("Проверка: Ровно 2 быстрых запроса (5 сек)", () -> 
                assertEquals(2L, fastRequests, "Должно быть 2 быстрых запроса")
            );

            Allure.step("Проверка: Ровно 2 задержанных запроса (10 сек)", () -> 
                assertEquals(2L, delayedRequests, "Должно быть 2 задержанных запроса")
            );
        });
        
        System.out.println("==============================\n");

        // 3. Проверки (Assertions) - их тоже можно обернуть в шаг для красоты отчета

    }

} // End of class TestMaxAxtive