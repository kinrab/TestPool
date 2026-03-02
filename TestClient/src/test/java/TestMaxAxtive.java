
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.List;
import java.util.stream.*;
import java.io.IOException;

public class TestMaxAxtive 
{

    @Test
    public void testPostgresPool() 
    {
        // 1. Создаем клиента
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        
        // 2. URL нашего сервлета:
        String url = "http://localhost:8080/MyServletProject/MyServlet";
        
        Instant testStart = Instant.now();
        System.out.println(">>> Start of test... Run requests to servlet...");

        // 3. Запускаем 4 задачи параллельно
     
        List<CompletableFuture<String>> futures = IntStream.rangeClosed(1, 4) // Тут надо 4 заменить на константу N - число запускаемых фич
        .mapToObj(id -> 
                    {
                        // Внутри mapToObj мы создаем задачу для каждого ID
                        return CompletableFuture.supplyAsync(() -> 
                                                                {
                                                                    // Этот блок кода выполняется в отдельном потоке
                                                                    Instant requestStart = Instant.now();
                                                                    try 
                                                                    {
                                                                        HttpRequest request = HttpRequest.newBuilder()
                                                                            .uri(URI.create(url))
                                                                            .build();

                                                                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                                                                        long totalTime = Duration.between(requestStart, Instant.now()).toSeconds();
                                                                        long startTimeRel = Duration.between(testStart, requestStart).toSeconds();

                                                                        return String.format("\nRequest #%d | Start on %d second | Duration %d seconds | Responce: %s\n\n", 
                                                                                             id, startTimeRel, totalTime, response.body().trim());
                                                                    } 
                                                                    catch (IOException | InterruptedException e)
                                                                    {
                                                                        return "Request #" + id + " Error: " + e.getMessage();
                                                                    }
                                                                }
                                                            );
                    }
                 )
        .collect(Collectors.toList());

        // 4. Ждем, пока все 4 потока закончат работу
        
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join(); // Старый вариант: CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 5. Выводим отчет в консоль
        System.out.println("\n======= Annual report =======");
        
        futures.forEach(f -> 
                            {
                              try 
                              { 
                                   System.out.println(f.get()); 
                              } 
                              catch (InterruptedException | ExecutionException e) 
                              {
                                   e.printStackTrace(); 
                              }
                            }
                       );
        
        System.out.println("==============================\n");
    }
    
} // End of class

