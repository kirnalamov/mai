package client;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import agents.*;
import model.*;
import io.DataLoader;

import java.io.IOException;
import java.util.*;

/**
 * Запуск клиентской части JADE (удаленный контейнер)
 * Запускается на машине 2 (или любой другой машине)
 * 
 * Использование:
 *   java -cp target/jade-delivery-system.jar client.ClientLauncher <server_host> [server_port] [client_name]
 * 
 * Пример:
 *   java -cp target/jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 Client2
 */
public class ClientLauncher {

    public static void main(String[] args) {
        // Поддержка запуска без аргументов для локального тестирования
        String serverHost = "localhost";
        int serverPort = 1099;  // Порт по умолчанию
        String clientName = "ClientContainer";
        
        if (args.length >= 1) {
            serverHost = args[0];
        }
        
        if (args.length < 1) {
            System.out.println("=== Локальный режим ===");
            System.out.println("Использование: ClientLauncher [server_host] [server_port] [client_name]");
            System.out.println("Пример: ClientLauncher localhost 1099 Client2");
            System.out.println("Или: ClientLauncher 192.168.1.100 1099 Client2");
            System.out.println("\nЗапуск с параметрами по умолчанию: localhost:1099\n");
        }

        if (args.length > 1) {
            try {
                serverPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("Неверный формат порта, используется порт по умолчанию: " + serverPort);
            }
        }

        if (args.length > 2) {
            clientName = args[2];
        }

        System.out.println("=== Запуск клиентской части JADE ===");
        System.out.println("Подключение к серверу: " + serverHost + ":" + serverPort);
        System.out.println("Имя контейнера: " + clientName);

        try {
            // Получаем Runtime JADE
            Runtime runtime = Runtime.instance();

            // Создаем профиль удаленного контейнера
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, serverHost);
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(serverPort));
            profile.setParameter(Profile.CONTAINER_NAME, clientName);

            // Создаем удаленный контейнер
            AgentContainer remoteContainer = runtime.createAgentContainer(profile);
            System.out.println("Удаленный контейнер создан и подключен к серверу");

            // Загружаем данные
            System.out.println("\nЗагрузка данных...");
            Map<String, Store> stores = loadStores();
            System.out.println("✓ Данные загружены");

            // КЛИЕНТ создает ВСЕ магазины (грузовики на сервере)
            System.out.println("\nСоздание магазинов на клиенте...");
            List<Store> storeList = new ArrayList<>(stores.values());
            
            int storeCount = 0;
            for (Store store : storeList) {
                Object[] args_store = new Object[]{store};
                AgentController storeController = remoteContainer.createNewAgent(
                    "store_" + store.getStoreId(),
                    "agents.StoreAgent",
                    args_store
                );
                storeController.start();
                System.out.println("✓ [CLIENT] StoreAgent запущен: store_" + store.getStoreId());
                storeCount++;
                
                // Небольшая задержка для стабильности
                Thread.sleep(100);
            }
            
            System.out.println("\n[CLIENT] Создано агентов:");
            System.out.println("  - Магазинов: " + storeCount);
            System.out.println("  - Грузовиков: 0 (находятся на сервере)");

            System.out.println("\n=== Клиент готов к работе ===");
            System.out.println("Подключено к серверу: " + serverHost + ":" + serverPort);
            System.out.println("Магазины отправляют запросы координатору...\n");

            // Ждем завершения работы всех магазинов
            waitForCompletion(remoteContainer, storeCount);
            
            System.out.println("\n=== Клиент завершает работу ===");
            remoteContainer.kill();
            System.exit(0);

        } catch (Exception e) {
            System.err.println("Ошибка при запуске клиента: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Map<String, Store> loadStores() throws IOException {
        Map<String, Store> map = new HashMap<>();
        try {
            List<Store> list = DataLoader.loadStores("data/stores.csv");
            for (Store s : list) {
                map.put(s.getStoreId(), s);
            }
        } catch (IOException e) {
            System.err.println("Ошибка загрузки магазинов: " + e.getMessage());
        }
        return map;
    }

    /**
     * Ожидание завершения работы всех агентов
     */
    private static void waitForCompletion(AgentContainer container, int expectedStores) {
        try {
            // Ждем достаточно времени для выполнения всех задач
            // (магазины отправляют запросы, координатор планирует, грузовики выполняют)
            Thread.sleep(30000); // 30 секунд должно хватить
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
