package planning;

import model.*;
import util.DistanceCalculator;

import java.time.LocalTime;
import java.util.*;

/**
 * Алгоритм планирования маршрутов доставки
 * Использует жадный алгоритм: ближайший магазин первым, с учётом временных окон
 */
public class RoutePlanningEngine {

    private static final int DEPOT_X = 0;
    private static final int DEPOT_Y = 0;

    /**
     * Основной метод планирования
     */
    public static List<DeliveryRoute> planRoutes(
            List<Truck> trucks,
            Map<String, Store> storesMap,
            Map<String, Product> productsMap,
            Map<String, List<DeliveryRequest>> demands) {

        List<DeliveryRoute> routes = new ArrayList<>();
        Map<String, List<DeliveryRequest>> remainingDemands = new HashMap<>(demands);

        LocalTime departureTime = LocalTime.of(9, 0);  // Выезд со склада в 9:00
        int routeCounter = 0;

        // Для каждого грузовика формируем маршрут
        for (Truck truck : trucks) {
            if (remainingDemands.isEmpty()) {
                break;
            }

            DeliveryRoute route = buildRoute(
                ++routeCounter,
                truck,
                storesMap,
                productsMap,
                remainingDemands,
                departureTime
            );

            if (!route.getStops().isEmpty()) {
                routes.add(route);
            }
        }

        return routes;
    }

    /**
     * Строит маршрут для одного грузовика
     */
    private static DeliveryRoute buildRoute(
            int routeId,
            Truck truck,
            Map<String, Store> storesMap,
            Map<String, Product> productsMap,
            Map<String, List<DeliveryRequest>> remainingDemands,
            LocalTime departureTime) {

        DeliveryRoute route = new DeliveryRoute(
            "ROUTE_" + routeId,
            truck.getTruckId(),
            departureTime
        );

        double currentX = truck.getStartX();
        double currentY = truck.getStartY();
        LocalTime currentTime = departureTime;
        double currentLoad = 0;
        double totalDistance = 0;

        // Жадный алгоритм: выбираем ближайший доступный магазин
        while (true) {
            String nextStoreId = findNearestStore(
                currentX, currentY, currentTime, currentLoad, truck.getCapacity(),
                storesMap, productsMap, remainingDemands
            );

            if (nextStoreId == null) {
                break;  // Нет больше доступных магазинов
            }

            Store store = storesMap.get(nextStoreId);
            List<DeliveryRequest> storeRequests = remainingDemands.get(nextStoreId);

            // Рассчитываем расстояние до магазина
            double distanceToStore = DistanceCalculator.calculateDistance(
                currentX, currentY, store.getX(), store.getY()
            );

            // Время в пути
            int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToStore);
            LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);

            // Проверяем, можем ли мы попасть в временное окно магазина
            if (!store.isWithinTimeWindow(arrivalTime)) {
                // Пытаемся приехать в начало временного окна
                arrivalTime = store.getTimeWindowStart();
            }

            // Создаём остановку
            DeliveryRoute.RouteStop stop = new DeliveryRoute.RouteStop(
                nextStoreId, store.getX(), store.getY()
            );
            stop.setDistanceFromPreviousStop(distanceToStore);
            stop.setArrivalTime(arrivalTime);

            // Добавляем товары в остановку
            double stopLoadWeight = 0;
            List<DeliveryRequest> delivered = new ArrayList<>();

            for (DeliveryRequest request : storeRequests) {
                Product product = productsMap.get(request.getProductId());

                if (currentLoad + stopLoadWeight + request.getTotalWeight() <= truck.getCapacity()) {
                    DeliveryRoute.DeliveryItem item = new DeliveryRoute.DeliveryItem(
                        request.getProductId(),
                        request.getQuantity(),
                        request.getTotalWeight()
                    );
                    stop.addItem(item);
                    stopLoadWeight += request.getTotalWeight();
                    delivered.add(request);
                    request.setStatus(DeliveryRequest.DeliveryStatus.DELIVERED);
                }
            }

            // Если ничего не добавили, пропускаем этот магазин
            // (возможно, товары не помещаются в грузовик)
            if (stop.getItems().isEmpty()) {
                // Удаляем только те заказы, которые были доставлены (если есть)
                if (!delivered.isEmpty()) {
                    remainingDemands.get(nextStoreId).removeAll(delivered);
                    if (remainingDemands.get(nextStoreId).isEmpty()) {
                        remainingDemands.remove(nextStoreId);
                    }
                }
                // Пропускаем этот магазин и ищем следующий
                continue;
            }

            // Время обслуживания
            int serviceTime = stop.getItems().size() * DistanceCalculator.calculateServiceTime();
            LocalTime departTime = arrivalTime.plusSeconds(serviceTime);
            stop.setDepartureTime(departTime);

            route.addStop(stop);
            currentLoad += stopLoadWeight;
            totalDistance += distanceToStore;
            currentX = store.getX();
            currentY = store.getY();
            currentTime = departTime;

            // Удаляем доставленные заказы
            remainingDemands.get(nextStoreId).removeAll(delivered);
            if (remainingDemands.get(nextStoreId).isEmpty()) {
                remainingDemands.remove(nextStoreId);
            }
        }

        // Возврат на склад
        if (!route.getStops().isEmpty()) {
            double distanceToDepot = DistanceCalculator.calculateDistance(
                currentX, currentY, truck.getStartX(), truck.getStartY()
            );
            totalDistance += distanceToDepot;

            int returnTime = DistanceCalculator.calculateTravelTime(distanceToDepot);
            LocalTime returnTime_local = currentTime.plusSeconds(returnTime);
            route.setEstimatedReturnTime(returnTime_local);
        }

        route.setTotalDistance(totalDistance);
        route.setTotalCost(DistanceCalculator.calculateCost(totalDistance, truck.getCostPerKm()));

        return route;
    }

    /**
     * Находит ближайший доступный магазин
     */
    private static String findNearestStore(
            double currentX, double currentY,
            LocalTime currentTime, double currentLoad, double capacity,
            Map<String, Store> storesMap,
            Map<String, Product> productsMap,
            Map<String, List<DeliveryRequest>> remainingDemands) {

        String nearestStoreId = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Map.Entry<String, List<DeliveryRequest>> entry : remainingDemands.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            String storeId = entry.getKey();
            Store store = storesMap.get(storeId);

            // Проверяем, есть ли место для товаров
            double requiredLoad = 0;
            for (DeliveryRequest req : entry.getValue()) {
                requiredLoad += req.getTotalWeight();
            }

            if (currentLoad + requiredLoad > capacity) {
                continue;  // Нет места в грузовике
            }

            double distance = DistanceCalculator.calculateDistance(
                currentX, currentY, store.getX(), store.getY()
            );

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestStoreId = storeId;
            }
        }

        return nearestStoreId;
    }
}
