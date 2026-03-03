
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private static final int TOMCAT_STARTUP_TIMEOUT_MS = 10000; // Константа для времени ожидания запуска (10 секунд)
    private static final int TOMCAT_SHUTDOWN_TIMEOUT_MS = 3000; // Константа для времени ожидания остановки (3 секунды)
    
    // Поднимаемся на уровень выше из TestClient и заходим в tomcat/bin
    private final String TOMCAT_BIN = System.getProperty("user.dir")  + File.separator + ".." + File.separator + "tomcat" + File.separator + "bin";

    @BeforeEach
    void startTomcat() throws IOException, InterruptedException 
    {
        System.out.println("Run Tomcat from: " + TOMCAT_BIN);
        
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
        pb.directory(new File(TOMCAT_BIN)); // Указываем рабочую директорию для процесса
        pb.start();
        
        Thread.sleep(TOMCAT_STARTUP_TIMEOUT_MS); // Пауза на развертывание
    }

    @AfterEach
    void stopTomcat() throws IOException, InterruptedException  
    {
        System.out.println("Stop Tomcat...");
        
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "shutdown.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();

        Thread.sleep(TOMCAT_SHUTDOWN_TIMEOUT_MS);  // Пауза на завершение работы
    }

    
    
    
    @Test
    public void testPostgresPool() 
    {
        // 0. Нужно запустить TomCat отталкиваясь от текущей директории - нужно поднять в папке workspace Jenkins:
        // нам понадобится класс ProcessBuilder для запуска внешних процессов.
        
        
        
        // 1. Создаем клиента
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        
        // 2. URL нашего сервлета:
        String url = "http://localhost:8080/MyServletProject/MyServlet";
        
        Instant testStart = Instant.now();
        System.out.println("\n>>> Start of test... Run requests to servlet...");

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
        
        // 6. Нужно остановить TomCat отталкиваясь от текущей директории - нужно поднять в папке workspace Jenkins:
        
        
    }   // Закончился тест testPostgresPool() 
    
} // End of class

