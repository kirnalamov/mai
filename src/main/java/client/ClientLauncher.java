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
        // String serverHost = "26.59.86.171";
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
            profile.setParameter(Profile.GUI, "true");
            profile.setParameter(Profile.MAIN_HOST, serverHost);
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(serverPort));
            profile.setParameter(Profile.CONTAINER_NAME, clientName);

            // Создаем удаленный контейнер
            AgentContainer remoteContainer = runtime.createAgentContainer(profile);
            System.out.println("Удаленный контейнер создан и подключен к серверу");

            // Загружаем данные
            System.out.println("\nЗагрузка данных...");
            // Магазины (координаты и временные окна)
            Map<String, Store> stores = loadStores();
            // Товары
            Map<String, Product> products = loadProducts();
            // Потребности магазинов: на каждый магазин может быть несколько товаров
            Map<String, List<DeliveryRequest>> demands = loadDemands(products);
            System.out.println("✓ Данные загружены");

            // КЛИЕНТ создает агентов магазинов: один агент на магазин со всеми потребностями
            System.out.println("\nСоздание магазинов на клиенте (один агент на магазин со всеми потребностями)...");
            int storeCount = 0;
            for (Map.Entry<String, List<DeliveryRequest>> entry : demands.entrySet()) {
                String storeId = entry.getKey();
                Store store = stores.get(storeId);
                if (store == null) {
                    System.err.println("[CLIENT] Магазин из demands не найден в списке магазинов: " + storeId);
                    continue;
                }
                List<DeliveryRequest> storeDemands = entry.getValue();
                if (storeDemands.isEmpty()) {
                    continue;
                }
                // Создаём один агент на магазин со всеми его потребностями
                Object[] args_store = new Object[]{store, storeDemands};
                String agentName = "store_" + storeId;
                AgentController storeController = remoteContainer.createNewAgent(
                    agentName,
                    "agents.StoreAgent",
                    args_store
                );
                storeController.start();
                System.out.println("✓ [CLIENT] StoreAgent запущен: " + agentName +
                        " (" + storeDemands.size() + " товаров)");
                storeCount++;

                // Небольшая задержка для стабильности
                Thread.sleep(100);
            }
            
            System.out.println("\n[CLIENT] Создано агентов:");
            System.out.println("  - Магазинов: " + storeCount);
            System.out.println("  - Грузовиков: 0 (находятся на сервере)");

            System.out.println("\n=== Клиент готов к работе ===");
            System.out.println("Подключено к серверу: " + serverHost + ":" + serverPort);
            System.out.println("Магазины самостоятельно договариваются с грузовиками о доставке...\n");

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

    private static Map<String, Product> loadProducts() throws IOException {
        Map<String, Product> map = new HashMap<>();
        try {
            List<Product> list = DataLoader.loadProducts("data/products.csv");
            for (Product p : list) {
                map.put(p.getProductId(), p);
            }
        } catch (IOException e) {
            System.err.println("Ошибка загрузки товаров: " + e.getMessage());
        }
        return map;
    }

    private static Map<String, List<DeliveryRequest>> loadDemands(Map<String, Product> products) throws IOException {
        try {
            // Используем data/stores.csv как источник потребностей (store_id, product_id, demand)
            return DataLoader.loadDemands("data/stores.csv", products);
        } catch (IOException e) {
            System.err.println("Ошибка загрузки потребностей магазинов: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Ожидание завершения работы всех агентов
     */
    private static void waitForCompletion(AgentContainer container, int expectedStores) {
        try {
            // Ждем достаточно времени для выполнения всех задач
            // (магазины ведут переговоры с грузовиками, грузовики выполняют доставки)
            Thread.sleep(30000); // 30 секунд должно хватить
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
