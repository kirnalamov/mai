package agents;

import io.DataLoader;
import io.ScheduleWriter;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import model.DeliveryRoute;
import model.Product;
import model.Store;
import model.Truck;
import util.DistanceCalculator;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пассивный агент-логгер.
 * Не принимает решений и не координирует агентов, а только
 * слушает сообщения о завершённых доставках и пишет сводку в output/schedule.*.
 */
public class ScheduleLoggerAgent extends Agent {

    private final List<DeliveryRoute> routes = new ArrayList<>();
    private final Map<String, Store> stores = new HashMap<>();
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Truck> trucks = new HashMap<>();
    private int routeCounter = 0;

    @Override
    protected void setup() {
        System.out.println("ScheduleLoggerAgent " + getLocalName() + " инициализирован");

        try {
            // Локальная информация для построения отчёта
            for (Store s : DataLoader.loadStores("data/stores.csv")) {
                stores.put(s.getStoreId(), s);
            }
            for (Product p : DataLoader.loadProducts("data/products.csv")) {
                products.put(p.getProductId(), p);
            }
            for (Truck t : DataLoader.loadTrucks("data/trucks.csv")) {
                trucks.put(t.getTruckId(), t);
            }
        } catch (IOException e) {
            System.err.println("[ScheduleLogger] Ошибка загрузки данных: " + e.getMessage());
        }

        addBehaviour(new LoggingBehaviour());
    }

    private class LoggingBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg != null) {
                String content = msg.getContent();
                if (content != null && content.startsWith("DELIVERY_COMPLETE:")) {
                    handleDeliveryComplete(content);
                }
            } else {
                block();
            }
        }

        private void handleDeliveryComplete(String content) {
            // Формат: DELIVERY_COMPLETE:storeId:productId:qty:truckId
            String[] parts = content.split(":");
            if (parts.length < 5) {
                System.err.println("[ScheduleLogger] Неверный формат сообщения: " + content);
                return;
            }

            String storeId = parts[1];
            String productId = parts[2];
            int quantity;
            try {
                quantity = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                System.err.println("[ScheduleLogger] Неверное количество: " + parts[3]);
                return;
            }
            String truckId = parts[4];

            Store store = stores.get(storeId);
            Product product = products.get(productId);
            Truck truck = trucks.get(truckId);

            if (store == null) {
                System.err.println("[ScheduleLogger] ВНИМАНИЕ: магазин не найден, используем координаты (0,0): store=" + storeId);
                store = new Store(storeId, 0, 0, LocalTime.now(), LocalTime.now().plusHours(1));
            }
            if (product == null) {
                System.err.println("[ScheduleLogger] ВНИМАНИЕ: товар не найден, вес будет считаться = 0: product=" + productId);
                product = new Product(productId, productId, 0.0);
            }
            if (truck == null) {
                System.err.println("[ScheduleLogger] ВНИМАНИЕ: грузовик не найден, используем стоимость за км = 0: truck=" + truckId);
                truck = new Truck(truckId, 1.0, 0.0, 0.0, 0.0);
            }

            double unitWeight = product.getUnitWeight();
            double totalWeight = unitWeight * quantity;

            // Строим простой маршрут "склад -> магазин"
            DeliveryRoute route = new DeliveryRoute("ROUTE_" + (++routeCounter), truckId, LocalTime.now());
            DeliveryRoute.RouteStop stop = new DeliveryRoute.RouteStop(storeId, store.getX(), store.getY());

            // Расстояние от точки старта грузовика до магазина
            double distanceToStore = DistanceCalculator.calculateDistance(
                    truck.getStartX(), truck.getStartY(), store.getX(), store.getY()
            );
            stop.setDistanceFromPreviousStop(distanceToStore);

            LocalTime arrival = LocalTime.now();
            stop.setArrivalTime(arrival);
            stop.setDepartureTime(arrival.plusMinutes(5));

            DeliveryRoute.DeliveryItem item = new DeliveryRoute.DeliveryItem(productId, quantity, totalWeight);
            stop.addItem(item);

            route.addStop(stop);
            route.setTotalDistance(distanceToStore * 2); // туда-обратно
            route.setTotalCost(DistanceCalculator.calculateCost(route.getTotalDistance(), truck.getCostPerKm()));

            routes.add(route);

            // Каждый раз перезаписываем актуальный отчёт
            try {
                File outDir = new File("output");
                if (!outDir.exists() && !outDir.mkdirs()) {
                    System.err.println("[ScheduleLogger] Не удалось создать директорию output");
                }

                System.out.println("[ScheduleLogger] Пишу расписание. Всего маршрутов: " + routes.size());
                ScheduleWriter.writeScheduleToCSV("output/schedule.csv", routes);
                ScheduleWriter.writeScheduleToExcel("output/schedule.xlsx", routes);
                System.out.println("[ScheduleLogger] Расписание обновлено в output/schedule.csv и output/schedule.xlsx");
            } catch (IOException e) {
                System.err.println("[ScheduleLogger] Ошибка записи отчёта: " + e.getMessage());
            }
        }
    }
}


