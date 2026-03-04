
import io.qameta.allure.Allure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.io.File;
import java.io.IOException;
import java.util.Comparator;

// Импорты для метода UpdateContextXml:
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestMaxActive 
{
    // Класс для хранения параметров нашего теста: 
    static class TestConfig 
    {
        // Входные параметры для тестов:
        String name;                                //  Имя или цель или описание смысла выполнения теста с этими параметрами.
        int maxActive;                              //  Значение MaxActive для текущего элемента скиска параметров. 
        long maxWait;                               //  Значение MaxWait для текущего элемента скиска параметров. Сколько ждать максимально завершения запроса HTTP в сервлет.
        long clientSleep;                           //  Значение Sleep для указания времени которое клиентский поток должен ждать освобождения коннекшена в пуле 
        int threads;                                //  Общее число фич/потоков запускаемых клиентом в текущем выполняемом тесте.
        
        // Ожидаемые результаты для ассертов:
        long expectedOk;                            //  Сколько запросов фич (потоков) дожлно быть завершено успешно в данном тесте. 
        long expectedFast;                          //  Сколько запросов фич (потоков) дожлно быть завершено успешно быстро без ожидания.  
        long expectedDelayed;                       //  Сколько запросов фич (потоков) дожлно быть завершено успешно после ожидания.
        long expectedError;                         //  Сколько запросов фич (потоков) должно быть завершено по таймауту с ошибкой после ожидания истечения таймаута.
        

        // Конструктор класса хранения параметров нагшего теста: 
        public TestConfig(String name, int maxActive, int maxWait, int clientSleep, int threads, long ok, long fast, long delayed,long err) 
        {
            this.name = name;
            this.maxActive = maxActive;
            this.maxWait = maxWait;
            this.clientSleep = clientSleep;
            this.threads = threads;
            this.expectedOk = ok;
            this.expectedFast = fast;
            this.expectedDelayed = delayed;
            this.expectedError = err; // Записываем ожидаемые ошибки
        }
        
        // Переопределим метод для удобства: JUnit 5 при отображении в дереве тестов (и Allure в заголовке) часто использует toString() объекта, если не указано иное.
        @Override
        public String toString() 
        {
            return name;
        }

    } // End of class TestConfig
    
    // Реальные значения параметров для наших тестов - если нужно добавить новых тестов просто здесь заполняем ноые строки:
    private static final List<TestConfig> SCENARIOS = 
            List.of(
                        //                Имя,                                                             MaxActive,       MaxWait,   Sleep,  Threads, ExpOk, ExpFast, ExpDelayed  ExpError
                          new TestConfig("Normal Wait  All:4 MaxAct:2 OK:4 Fast:2 Delay:2 Error:0",          2,           10000,      5000,     4,     4L,     2L,      2L,          0L   ), 
                          new TestConfig("Timeout Fail All:4 MaxAct:2 Ok:2 Fast:2 Delay:0 Error:2",          2,            3000,      5000,     4,     2L,     2L,      0L,          2L   ),
                          new TestConfig("HARD LIMIT (MaxAct:1)",                                            1,            30000,     5000,     4,     4L,     1L,      3L,          0L   ) 
                   );
    
    // Класс для хранения результатов теста:
    static class TestResult 
    {
        int code;
        long startOffset;
        long duration;
        String body; 
        
        TestResult(int code, long startOffset, long duration, String body) 
        {
            this.code = code;
            this.startOffset = startOffset;
            this.duration = duration;
            this.body = body;
        }
    }
    
    // Константы для запуска и остановки TomCat:
    private static final int TOMCAT_STARTUP_TIMEOUT_MS = 10000;
    private static final int TOMCAT_SHUTDOWN_TIMEOUT_MS = 3000;
    private final String TOMCAT_BIN = System.getProperty("user.dir") + File.separator + ".." + File.separator + "tomcat" + File.separator + "bin";

    // Отказываемся от этого элемента  @BeforeEach так как нам нужно иметь доступ к параметрами каждого теста.
    // Метод запуска TomCat:
    void startTomcat() throws IOException, InterruptedException 
    {
        System.out.println("Run Tomcat from: " + TOMCAT_BIN);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();
        Thread.sleep(TOMCAT_STARTUP_TIMEOUT_MS);
    }

    // Ну тогда логично отказаться и от этого элемента: @AfterEach
    // Метод остановки TomCat
    void stopTomcat() throws IOException, InterruptedException 
    {
        System.out.println("Stop Tomcat...");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "shutdown.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();
        Thread.sleep(TOMCAT_SHUTDOWN_TIMEOUT_MS);
    }

    // Метод который обновляет параметры MAxActive и MaxWaitMillis в context.xml перед стартом TomCat: 
    private void updateContextXml(TestConfig config) throws Exception 
    {
        // 1. Собираем путь к файлу (на уровень выше от bin, в папку conf) -> "tomcat\conf\context.xml" будем править главный конфиг TomCat - он имеет приоритет!   
        String contextPath = TOMCAT_BIN + File.separator + ".." + File.separator + "conf" + File.separator + "context.xml";



        // 2. Создаем указатель на файл:
        File xmlFile = new File(contextPath);            
        System.out.println(">>> [CONFIG] Читаем файл: " + xmlFile.getCanonicalPath());   //полный абсолютный путь  xmlFile.getCanonicalPath()

        // 3. Инициализируем XML-парсер
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        
        // 4. Читаем содержимое файла: 
        //    Вот здесь DBuilder (наш парсер) берет тот адрес, который мы ему дали в xmlFile, идет на диск, открывает файл, вычитывает все байты и закрывает его,
        //    оставив у себя в памяти «дерево» (объект doc).
        Document doc = builder.parse(xmlFile);

        // 3. Ищем все теги <Resource> и меняем значения на наши из переметров теста:
        NodeList resources = doc.getElementsByTagName("Resource");
        boolean isUpdated = false;

        for (int i = 0; i < resources.getLength(); i++) 
        {
            Element resource = (Element) resources.item(i);

            // Ищем наш ресурс по ключевому признаку (например, драйверу или имени)
            if (resource.getAttribute("driverClassName").contains("postgresql") || resource.getAttribute("name").contains("jdbc/postgres")) 
            {

                // Записываем новые значения из нашего текущего сценария
                resource.setAttribute("maxTotal", String.valueOf(config.maxActive));
                resource.setAttribute("maxWaitMillis", String.valueOf(config.maxWait));
                isUpdated = true;
            }
        }

        // Проверяем были ли изменены параметры или мы их не нашли? 
        if (!isUpdated) 
        {
            throw new RuntimeException("Error: В context.xml не найден Resource для PostgreSQL!");
        }

        // 4. Сохраняем изменения обратно в файл
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        
        // 5. Добавим форматирование, чтобы XML не превратился в одну строку
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(xmlFile);
        transformer.transform(source, result);

        // 6. Нужно быть уверенным, что файл на диске точно получил наши параметры: 
        System.out.flush();
        
        // 7. Даем ОС микро-паузу (500 мс), чтобы закрыть все дескрипторы файла
        Thread.sleep(500); 
                
        System.out.println(">>> [CONFIG] Успешно установлены: maxTotal = " + config.maxActive + ", maxWaitMillis = " + config.maxWait);
        
    } // End of UpdateContextXml
    
    
    // **************************************************************************************************
    // Самый важный метод - наш параметризованный тест. 
    //***************************************************************************************************
    static List<TestConfig> getScenarios() 
    {
        return SCENARIOS;
    }
    
    @Feature("Postgres Connection Pool")
    @Story("MaxActive limit check")
    @Description("Проверка очереди Tomcat: запроса сразу, и в ожидании.")
    
    // 2. Параметризованный запуск
    @ParameterizedTest(name = "{0}")
    @MethodSource("getScenarios")
    
    public void testPostgresPool(TestConfig testScenarioParameters) throws Exception 
    {
        int N = 4;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        String url = "http://localhost:8080/MyServletProject/MyServlet";

        System.out.println("\n>>> Start of test... Run requests to servlet...");

        // Добавим очистку директория work в TomCat чтобы гарантировать чтение параметров из context.xml
        Allure.step("Очистка кэша Tomcat (work & temp)", () -> 
        {
        File workDir = new File(TOMCAT_BIN + "/../work");
        File tempDir = new File(TOMCAT_BIN + "/../temp");

        if (workDir.exists()) deleteDirectory(workDir);
        if (tempDir.exists()) deleteDirectory(tempDir);

        System.out.println(">>> [CLEANUP] Папки work и temp удалены.");
        });

        
        
        // Шаг 0. Перед тестом заполним правильными значениями MAxActive и MaxWaitMillis
        Allure.step("0. Подготовка: Установка параметров пула (maxTotal=" + testScenarioParameters.maxActive + ", maxWait=" + testScenarioParameters.maxWait + ")", () -> 
        {
            updateContextXml(testScenarioParameters);
        });

        
        // Шаг 1: Запустим TomCat:
        Allure.step("1. Запуск Tomcat", () -> 
        {
            startTomcat();
        });
        
        // ВОТ ЗДЕСЬ ОБНУЛЯЕМ ТАЙМЕР:
        Instant testStart = Instant.now(); 
        System.out.println("\n>>> Запуск нагрузки (Time start: 0s)");

        // ШАГ 2. ЗАПУСКАЕМ N потоков с HTTP-запросом GET:
        List<TestResult> results = Allure.step("2. Отправка " + N + " параллельных запросов к сервлету", () -> 
        {
            List<CompletableFuture<TestResult>> futures = IntStream.rangeClosed(1, N) // Т - число запускаемых клиентских запросов (фич / потоков)
                .mapToObj(id -> CompletableFuture.supplyAsync
                            (   () -> 
                                    {
                                        Instant requestStart = Instant.now();
                                        try {
                                            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
                                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                                            long duration = Duration.between(requestStart, Instant.now()).toSeconds();
                                            long startOffset = Duration.between(testStart, requestStart).toSeconds();

                                            
                                              // --- ВОТ ЭТОТ БЛОК ВЫВЕДЕТ ВСЁ В КОНСОЛЬ ---
                                                synchronized (System.out) 
                                                {
                                                    System.out.println("\n[HTTP RESPONSE DEBUG] Request ID: " + id);
                                                    System.out.println("Status Code: " + response.statusCode());
                                                    System.out.println("Body: " + response.body());
                                                    System.out.println("----------------------");
                                                }
                                                
                                            return new TestResult(response.statusCode(), startOffset, duration, response.body());
                                        } catch (Exception e) {
                                            return new TestResult(500, -1, -1,"");
                                        }
                                    }
                            )
                         )
                .collect(Collectors.toList());

            // Ждем здесь же, внутри шага, чтобы Allure замерил полное время выполнения всех потоков
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // Возвращаем результат из шага наружу в переменную results
            return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        });

        //Шаг 3: Останавливаем TomCat:
        stopTomcat();
        
        
        // ШАГ 4: Формируем детальный отчет:
        Allure.step("4. Формирование детального отчета по запросам", () -> {
            System.out.println("\n======= Detail report =======");
            for (int i = 0; i < results.size(); i++) {
                TestResult r = results.get(i);
                String logLine = String.format("Request #%d | Start on %d sec | Duration %d sec | Status: %d", 
                                                (i + 1), r.startOffset, r.duration, r.code);
                System.out.println(logLine);
                // Добавляем строчку лога прямо в Allure как вложение или текст
                Allure.addAttachment("Request " + (i+1), logLine);
            }
        });
        
        // ШАГ 5: Логирование в отчет Allure и универсальные проверки Asserts
        Allure.step("Проверка результатов (Assertions)", () -> 
        {
            // 0. ОБЪЯВЛЯЕМ переменные в начале блока, чтобы их видели все вложенные шаги
            final long sleep = testScenarioParameters.clientSleep / 1000; 
            final int maxActive = testScenarioParameters.maxActive;

            // 1. Считаем РЕАЛЬНЫЕ успехи (код 200 И в теле нет фразы об ошибке)
            long realOkCount = results.stream()
                .filter(r -> r.code == 200 && !r.body.contains("SQL query execution error"))
                .count();

            // 2. Считаем РЕАЛЬНЫЕ ошибки (либо код 500, либо 200 с текстом ошибки)
            long realErrorCount = results.stream()
                .filter(r -> r.code == 500 || (r.code == 200 && r.body.contains("SQL query execution error")))
                .count();

            // 3. Проверка количества
            Allure.step("Проверка: Успешных ответов с данными (ожидаем " + testScenarioParameters.expectedOk + ")", () -> 
                assertEquals(testScenarioParameters.expectedOk, realOkCount, "Кол-во чистых 200 не совпало!")
            );

            Allure.step("Проверка: Отвалов пула (ожидаем " + testScenarioParameters.expectedError + ")", () -> 
                assertEquals(testScenarioParameters.expectedError, realErrorCount, "Ожидаемые ошибки не найдены в ответах!")
            );

            // 4. Лесенка времени (только для тех, где реально были данные)
            List<TestResult> sortedOkResults = results.stream()
                .filter(r -> r.code == 200 && !r.body.contains("SQL query execution error"))
                .sorted(Comparator.comparingLong(r -> r.duration))
                .collect(Collectors.toList());

            for (int i = 0; i < sortedOkResults.size(); i++)  
            {
                final int requestIndex = i;
                // Номер "волны" (0, 1, 2...), в которую попал запрос исходя из maxActive
                int wave = i / maxActive; 
                long expectedDuration = sleep * (wave + 1);

                Allure.step("Проверка запроса в волне #" + (wave + 1) + " (ожидаем ~" + expectedDuration + "с)", () -> 
                {
                    long actualDuration = sortedOkResults.get(requestIndex).duration;
                    assertTrue(Math.abs(actualDuration - expectedDuration) <= 1, 
                        "Запрос #" + (requestIndex + 1) + " в очереди длился " + actualDuration + "с вместо " + expectedDuration + "с");
                });
            }
        }); // Конец главного Allure.step
        
        System.out.println("============ Усе готово. Фас... Профиль... Отпечатки пальцев... :-) ==================\n");

    } // End of test 
    
    // Метод очистки директория в TomCat
    private void deleteDirectory(File directoryToBeDeleted) 
    {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) 
        {
            for (File file : allContents) 
            {
                deleteDirectory(file); // Рекурсивно заходим в папки
            }
        }
        directoryToBeDeleted.delete(); // Удаляем сам файл или пустую папку
    }

} // End of class TestMaxActive