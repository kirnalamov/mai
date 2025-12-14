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
        
        // Добавляем время погрузки на базе (10 минут) перед началом маршрута
        int loadingTimeSeconds = DistanceCalculator.calculateLoadingTime();
        currentTime = currentTime.plusSeconds(loadingTimeSeconds);
        
        double currentLoad = 0;
        double totalDistance = 0;

        // Оптимизация с учетом стоимости и времени доставки
        while (true) {
            String nextStoreId = findNearestStore(
                currentX, currentY, currentTime, currentLoad, truck.getCapacity(),
                truck.getCostPerKm(), truck.getStartX(), truck.getStartY(),
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

            // Время обслуживания (разгрузка зависит от количества товаров)
            int totalItems = stop.getItems().stream().mapToInt(DeliveryRoute.DeliveryItem::getQuantity).sum();
            int serviceTime = DistanceCalculator.calculateServiceTime(totalItems);
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
        double distanceToDepot = 0;
        if (!route.getStops().isEmpty()) {
            distanceToDepot = DistanceCalculator.calculateDistance(
                currentX, currentY, truck.getStartX(), truck.getStartY()
            );
            totalDistance += distanceToDepot;

            int returnTime = DistanceCalculator.calculateTravelTime(distanceToDepot);
            LocalTime returnTime_local = currentTime.plusSeconds(returnTime);
            route.setEstimatedReturnTime(returnTime_local);
        }

        route.setTotalDistance(totalDistance);
        
        // Рассчитываем стоимость с учетом обратного пути * 0.7
        if (!route.getStops().isEmpty() && distanceToDepot > 0) {
            // Стоимость = путь туда (все расстояния между точками) + обратный путь от последней точки до базы * 0.7
            double forwardDistance = totalDistance - distanceToDepot; // Расстояние без обратного пути
            double cost = DistanceCalculator.calculateCostWithReturn(forwardDistance, distanceToDepot, truck.getCostPerKm());
            route.setTotalCost(cost);
        } else {
            route.setTotalCost(0);
        }

        return route;
    }

    /**
     * Коэффициент веса для стоимости (0.0 - только время, 1.0 - только стоимость)
     * 0.3 означает 30% веса на стоимость, 70% на время доставки
     */
    private static final double COST_WEIGHT = 0.3;
    private static final double TIME_WEIGHT = 1.0 - COST_WEIGHT;

    /**
     * Находит оптимальный магазин с учетом стоимости и времени доставки
     */
    private static String findNearestStore(
            double currentX, double currentY,
            LocalTime currentTime, double currentLoad, double capacity,
            double costPerKm, double depotX, double depotY,
            Map<String, Store> storesMap,
            Map<String, Product> productsMap,
            Map<String, List<DeliveryRequest>> remainingDemands) {

        String bestStoreId = null;
        double bestScore = Double.MAX_VALUE;
        
        // Для нормализации находим максимальные значения стоимости и времени
        double maxCost = 0;
        long maxTimeSeconds = 0;
        
        // Первый проход: находим максимальные значения для нормализации
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
            
            // Стоимость: путь туда + обратный путь от магазина до базы * 0.7
            double distanceFromStoreToBase = DistanceCalculator.calculateDistance(
                    store.getX(), store.getY(), depotX, depotY
            );
            double cost = DistanceCalculator.calculateCostWithReturn(distance, distanceFromStoreToBase, costPerKm);
            int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
            LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
            
            // Учитываем ожидание до начала окна магазина
            if (arrivalTime.isBefore(store.getTimeWindowStart())) {
                long waitSeconds = java.time.Duration.between(arrivalTime, store.getTimeWindowStart()).getSeconds();
                travelTimeSeconds += waitSeconds;
            }
            
            maxCost = Math.max(maxCost, cost);
            maxTimeSeconds = Math.max(maxTimeSeconds, travelTimeSeconds);
        }
        
        // Второй проход: выбираем лучший магазин по комбинированному критерию
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
            
            // Рассчитываем стоимость: путь туда + обратный путь от магазина до базы * 0.7
            double distanceFromStoreToBase = DistanceCalculator.calculateDistance(
                    store.getX(), store.getY(), depotX, depotY
            );
            double cost = DistanceCalculator.calculateCostWithReturn(distance, distanceFromStoreToBase, costPerKm);
            
            // Рассчитываем время доставки
            int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
            LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
            
            // Учитываем ожидание до начала окна магазина
            if (arrivalTime.isBefore(store.getTimeWindowStart())) {
                long waitSeconds = java.time.Duration.between(arrivalTime, store.getTimeWindowStart()).getSeconds();
                travelTimeSeconds += waitSeconds;
            }
            
            // Нормализуем значения (избегаем деления на ноль)
            double normalizedCost = maxCost > 0 ? cost / maxCost : 0;
            double normalizedTime = maxTimeSeconds > 0 ? (double)travelTimeSeconds / maxTimeSeconds : 0;
            
            // Комбинированный score: меньше = лучше
            double score = COST_WEIGHT * normalizedCost + TIME_WEIGHT * normalizedTime;

            if (score < bestScore) {
                bestScore = score;
                bestStoreId = storeId;
            }
        }

        return bestStoreId;
    }
}
