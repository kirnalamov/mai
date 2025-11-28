package server;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.AgentState;
import agents.*;
import model.*;
import io.DataLoader;

import java.io.IOException;
import java.util.*;

/**
 * Запуск серверной части JADE (главный контейнер)
 * Запускается на машине 1
 * 
 * Использование:
 *   java -cp target/jade-delivery-system.jar server.ServerLauncher [port]
 */
public class ServerLauncher {

    public static void main(String[] args) {
        int port = 1099;  // Порт по умолчанию
        
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Неверный формат порта, используется порт по умолчанию: " + port);
            }
        }

        System.out.println("=== Запуск серверной части JADE ===");
        System.out.println("Порт: " + port);

        try {
            // Получаем Runtime JADE
            Runtime runtime = Runtime.instance();

            // Создаем профиль главного контейнера
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.GUI, "true");
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(port));
            profile.setParameter(Profile.CONTAINER_NAME, "MainContainer");
            profile.setParameter(Profile.PLATFORM_ID, "DeliveryPlatform");

            // Создаем главный контейнер
            AgentContainer mainContainer = runtime.createMainContainer(profile);
            System.out.println("Главный контейнер создан");

            // Загружаем данные
            System.out.println("\nЗагрузка данных...");
            Map<String, Product> products = loadProducts();
            Map<String, Store> stores = loadStores();
            List<Truck> trucks = loadTrucks();
            System.out.println("✓ Данные загружены успешно");
            System.out.println("  - Товаров: " + products.size());
            System.out.println("  - Магазинов: " + stores.size());
            System.out.println("  - Грузовиков: " + trucks.size());

            // Создаем и запускаем Coordinator Agent
            System.out.println("\nСоздание агентов...");
            AgentController coordinatorController = mainContainer.createNewAgent(
                "coordinator",
                "agents.CoordinatorAgent",
                null
            );
            coordinatorController.start();
            System.out.println("✓ CoordinatorAgent запущен");

            // Создаем и запускаем Warehouse Agent
            AgentController warehouseController = mainContainer.createNewAgent(
                "warehouse",
                "agents.WarehouseAgent",
                null
            );
            warehouseController.start();
            System.out.println("✓ WarehouseAgent запущен");

            // СЕРВЕР создает ВСЕ грузовики (магазины будут на клиенте)
            System.out.println("\nСоздание грузовиков на сервере...");
            int truckCount = 0;
            for (Truck truck : trucks) {
                Object[] args_truck = new Object[]{truck};
                AgentController truckController = mainContainer.createNewAgent(
                    truck.getTruckId(),
                    "agents.TruckAgent",
                    args_truck
                );
                truckController.start();
                System.out.println("✓ [SERVER] TruckAgent запущен: " + truck.getTruckId());
                truckCount++;
                Thread.sleep(100);
            }
            
            System.out.println("\n[SERVER] Создано агентов:");
            System.out.println("  - Координатор: 1");
            System.out.println("  - Склад: 1");
            System.out.println("  - Грузовиков: " + truckCount);
            System.out.println("  - Магазинов: 0 (будут созданы на клиенте)");

            System.out.println("\n=== Сервер готов к работе ===");
            System.out.println("Для подключения клиентов используйте адрес: localhost:" + port);
            System.out.println("Ожидание подключения клиента и выполнения задач...\n");

            // Ждем завершения работы координатора
            waitForCompletion(coordinatorController);
            
            System.out.println("\n=== Сервер завершает работу ===");
            mainContainer.kill();
            System.exit(0);

        } catch (Exception e) {
            System.err.println("Ошибка при запуске сервера: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Map<String, Product> loadProducts() throws IOException {
        Map<String, Product> map = new HashMap<>();
        List<Product> list = DataLoader.loadProducts("data/products.csv");
        for (Product p : list) {
            map.put(p.getProductId(), p);
        }
        return map;
    }

    private static Map<String, Store> loadStores() throws IOException {
        Map<String, Store> map = new HashMap<>();
        List<Store> list = DataLoader.loadStores("data/stores.csv");
        for (Store s : list) {
            map.put(s.getStoreId(), s);
        }
        return map;
    }

    private static List<Truck> loadTrucks() throws IOException {
        return DataLoader.loadTrucks("data/trucks.csv");
    }
    
    /**
     * Ожидание завершения работы координатора
     */
    private static void waitForCompletion(AgentController coordinatorController) {
        try {
            // Ждем пока координатор не завершит работу
            while (true) {
                Thread.sleep(2000);
                // Проверяем статус координатора
                jade.wrapper.State state = coordinatorController.getState();
                if (state != null && state.getCode() == AgentState.cAGENT_STATE_DELETED) {
                    break;
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки
        }
    }
}
