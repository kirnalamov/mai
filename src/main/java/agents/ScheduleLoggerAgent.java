package agents;

import io.DataLoader;
import io.ScheduleWriter;
import jade.core.Agent;
import jade.core.AID;
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
    // Активные маршруты: ключ = "truckId:departureTime", значение = DeliveryRoute
    private final Map<String, DeliveryRoute> activeRoutes = new HashMap<>();
    // Условная длительность обслуживания в магазине (минуты) —
    // должна совпадать с SERVICE_MINUTES в TruckAgent
    private static final int SERVICE_MINUTES = 30;

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
                System.out.println("[ScheduleLogger] Получено сообщение от " + msg.getSender().getName() + ": " + content);
                if (content != null && content.startsWith("DELIVERY_COMPLETE:")) {
                    System.out.println("[ScheduleLogger] ✓ Обрабатываю сообщение о доставке");
                    handleDeliveryComplete(content);
                } else {
                    System.out.println("[ScheduleLogger] ⚠ Игнорирую сообщение (не DELIVERY_COMPLETE): " + content);
                }
            } else {
                block();
            }
        }

        private void handleDeliveryComplete(String content) {
            // Формат: DELIVERY_COMPLETE:storeId:productId:qty:truckId:departureTime:arrivalTime:departureFromStore:distanceFromPrevious:storeX:storeY
            // Старый формат (для обратной совместимости): DELIVERY_COMPLETE:storeId:productId:qty:truckId:plannedStart:plannedEnd
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

            LocalTime departureTime = null;
            LocalTime arrivalTime = null;
            LocalTime departureFromStore = null;
            double distanceFromPrevious = 0.0;
            double storeX = 0.0;
            double storeY = 0.0;
            
            // Новый формат с тремя временами и расстоянием (departureTime:arrivalTime:departureFromStore:distanceFromPrevious:storeX:storeY)
            if (parts.length >= 11) {
                try {
                    // В сообщении от грузовика время кодируется в формате HH.mm
                    java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH.mm");
                    departureTime = LocalTime.parse(parts[5], fmt);
                    arrivalTime = LocalTime.parse(parts[6], fmt);
                    departureFromStore = LocalTime.parse(parts[7], fmt);
                    distanceFromPrevious = Double.parseDouble(parts[8]);
                    storeX = Double.parseDouble(parts[9]);
                    storeY = Double.parseDouble(parts[10]);
                } catch (Exception e) {
                    System.err.println("[ScheduleLogger] Не удалось распарсить времена доставки: " + e.getMessage());
                }
            }
            // Формат с тремя временами без расстояния (departureTime:arrivalTime:departureFromStore)
            else if (parts.length >= 8) {
                try {
                    java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH.mm");
                    departureTime = LocalTime.parse(parts[5], fmt);
                    arrivalTime = LocalTime.parse(parts[6], fmt);
                    departureFromStore = LocalTime.parse(parts[7], fmt);
                } catch (Exception e) {
                    System.err.println("[ScheduleLogger] Не удалось распарсить времена доставки: " + e.getMessage());
                }
            }
            // Старый формат (обратная совместимость)
            else if (parts.length >= 7) {
                try {
                    java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH.mm");
                    departureTime = LocalTime.parse(parts[5], fmt);
                    arrivalTime = departureTime; // В старом формате нет отдельного времени прибытия
                    departureFromStore = LocalTime.parse(parts[6], fmt);
                } catch (Exception e) {
                    System.err.println("[ScheduleLogger] Не удалось распарсить плановые времена доставки, будет использовано текущее время");
                }
            }

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
                truck = new Truck(truckId, 1.0, 0.0, 0.0, 0.0,
                        LocalTime.of(8, 0), LocalTime.of(18, 0));
            }

            double unitWeight = product.getUnitWeight();
            double totalWeight = unitWeight * quantity;

            // Время выезда грузовика (если не передано, используем время начала работы 08:00)
            LocalTime routeDeparture = departureTime != null ? departureTime : LocalTime.of(8, 0);
            
            // Ключ для группировки маршрутов: грузовик + время выезда
            String routeKey = truckId + ":" + routeDeparture.toString();
            
            // Ищем существующий маршрут или создаем новый
            DeliveryRoute route = activeRoutes.get(routeKey);
            if (route == null) {
                // Создаем новый маршрут
                route = new DeliveryRoute("ROUTE_" + (++routeCounter), truckId, routeDeparture);
                route.setTruckAvailabilityStart(truck.getAvailabilityStart());
                route.setTruckAvailabilityEnd(truck.getAvailabilityEnd());
                activeRoutes.put(routeKey, route);
                routes.add(route);
            }
            
            // Используем координаты из сообщения, если переданы, иначе из справочника
            double stopX = (storeX != 0.0 || storeY != 0.0) ? storeX : store.getX();
            double stopY = (storeX != 0.0 || storeY != 0.0) ? storeY : store.getY();
            
            // Ищем существующую остановку в этом маршруте (если товары доставляются в тот же магазин)
            DeliveryRoute.RouteStop stop = null;
            for (DeliveryRoute.RouteStop existingStop : route.getStops()) {
                if (existingStop.getStoreId().equals(storeId) && 
                    Math.abs(existingStop.getX() - stopX) < 0.01 && 
                    Math.abs(existingStop.getY() - stopY) < 0.01) {
                    stop = existingStop;
                    break;
                }
            }
            
            if (stop == null) {
                // Создаем новую остановку
                stop = new DeliveryRoute.RouteStop(storeId, stopX, stopY);
                
                // Расстояние от предыдущей остановки
                if (distanceFromPrevious > 0.0) {
                    // Используем переданное расстояние
                    stop.setDistanceFromPreviousStop(distanceFromPrevious);
                } else {
                    // Рассчитываем расстояние от последней остановки или от склада
                    if (route.getStops().isEmpty()) {
                        // Первая остановка - расстояние от склада
                        double dist = DistanceCalculator.calculateDistance(
                                truck.getStartX(), truck.getStartY(), stopX, stopY);
                        stop.setDistanceFromPreviousStop(dist);
                    } else {
                        // От последней остановки
                        DeliveryRoute.RouteStop lastStop = route.getStops().get(route.getStops().size() - 1);
                        double dist = DistanceCalculator.calculateDistance(
                                lastStop.getX(), lastStop.getY(), stopX, stopY);
                        stop.setDistanceFromPreviousStop(dist);
                    }
                }
                
                // Используем точные времена, переданные от TruckAgent
                if (arrivalTime != null && departureFromStore != null) {
                    stop.setArrivalTime(arrivalTime);
                    stop.setDepartureTime(departureFromStore);
                } else if (departureTime != null) {
                    // Fallback: рассчитываем приблизительно
                    double dist = stop.getDistanceFromPreviousStop();
                    int travelTimeSeconds = DistanceCalculator.calculateTravelTime(dist);
                    LocalTime arrival = departureTime.plusSeconds(travelTimeSeconds);
                    // Время разгрузки будет пересчитано после добавления товара
                    stop.setArrivalTime(arrival);
                }
                
                route.addStop(stop);
            }
            
            // Добавляем товар в остановку
            DeliveryRoute.DeliveryItem item = new DeliveryRoute.DeliveryItem(productId, quantity, totalWeight);
            stop.addItem(item);
            
            // Пересчитываем время разгрузки с учетом всех товаров в остановке
            if (stop.getArrivalTime() != null && stop.getDepartureTime() == null) {
                int totalItems = stop.getItems().stream().mapToInt(DeliveryRoute.DeliveryItem::getQuantity).sum();
                int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(totalItems);
                LocalTime departure = stop.getArrivalTime().plusSeconds(serviceTimeSeconds);
                stop.setDepartureTime(departure);
            }
            
            // Пересчитываем общее расстояние маршрута (накопительно)
            double totalRouteDistance = 0.0;
            for (DeliveryRoute.RouteStop s : route.getStops()) {
                totalRouteDistance += s.getDistanceFromPreviousStop();
            }
            // Добавляем расстояние возврата на склад
            if (!route.getStops().isEmpty()) {
                DeliveryRoute.RouteStop lastStop = route.getStops().get(route.getStops().size() - 1);
                double distanceToDepot = DistanceCalculator.calculateDistance(
                        lastStop.getX(), lastStop.getY(), truck.getStartX(), truck.getStartY());
                totalRouteDistance += distanceToDepot;
                
                // Обновляем время возвращения на склад
                LocalTime lastDeparture = lastStop.getDepartureTime();
                if (lastDeparture != null) {
                    int returnTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToDepot);
                    LocalTime estimatedReturnTime = lastDeparture.plusSeconds(returnTimeSeconds);
                    route.setEstimatedReturnTime(estimatedReturnTime);
                }
            }
            route.setTotalDistance(totalRouteDistance);
            route.setTotalCost(DistanceCalculator.calculateCost(totalRouteDistance, truck.getCostPerKm()));

            // Отправляем уведомление магазину о доставке
            try {
                AID storeAID = new AID("store_" + storeId, AID.ISLOCALNAME);
                ACLMessage storeNotification = new ACLMessage(ACLMessage.INFORM);
                storeNotification.addReceiver(storeAID);
                storeNotification.setContent("DELIVERY_COMPLETE:" + storeId + ":" + productId + ":" + quantity + ":" + truckId);
                send(storeNotification);
                System.out.println("[ScheduleLogger] → Отправлено уведомление магазину " + storeId + " о доставке " + productId);
            } catch (Exception e) {
                // Магазин может быть на другом контейнере, это нормально
                System.out.println("[ScheduleLogger] Не удалось отправить уведомление магазину " + storeId + " (возможно, на другом контейнере)");
            }

            // Каждый раз перезаписываем актуальный отчёт
            try {
                File outDir = new File("output");
                if (!outDir.exists()) {
                    boolean created = outDir.mkdirs();
                    if (!created) {
                        System.err.println("[ScheduleLogger] ❌ Не удалось создать директорию output: " + outDir.getAbsolutePath());
                        return;
                    } else {
                        System.out.println("[ScheduleLogger] ✓ Создана директория output: " + outDir.getAbsolutePath());
                    }
                }

                System.out.println("[ScheduleLogger] 📝 Пишу расписание. Всего маршрутов: " + routes.size());
                if (routes.isEmpty()) {
                    System.out.println("[ScheduleLogger] ⚠ ПРЕДУПРЕЖДЕНИЕ: нет маршрутов для записи! Возможно, агенты не договорились о доставках.");
                    System.out.println("[ScheduleLogger] ⚠ Проверьте логи StoreAgent и TruckAgent на наличие ошибок договоренности.");
                } else {
                    System.out.println("[ScheduleLogger] Записываю CSV файл...");
                    ScheduleWriter.writeScheduleToCSV("output/schedule.csv", routes);
                    System.out.println("[ScheduleLogger] Записываю Excel файл...");
                    ScheduleWriter.writeScheduleToExcel("output/schedule.xlsx", routes);
                    System.out.println("[ScheduleLogger] ✓✓✓ Расписание успешно обновлено в output/schedule.csv и output/schedule.xlsx");
                }
            } catch (IOException e) {
                System.err.println("[ScheduleLogger] ❌ ОШИБКА записи отчёта: " + e.getMessage());
                System.err.println("[ScheduleLogger] Полный стек ошибки:");
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("[ScheduleLogger] ❌ Неожиданная ошибка при записи отчёта: " + e.getMessage());
                System.err.println("[ScheduleLogger] Полный стек ошибки:");
                e.printStackTrace();
            }
        }
    }
}


