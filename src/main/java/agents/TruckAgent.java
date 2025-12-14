package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Truck;
import model.Product;
import model.Store;
import io.DataLoader;
import util.DistanceCalculator;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.io.IOException;

/**
 * Агент грузовика
 * Самостоятельно принимает решения о приеме заказов от магазинов и выполняет доставки
 */
public class TruckAgent extends Agent {
    private Truck truck;
    private Map<String, Product> products; // Справочник товаров для расчёта веса
    private Map<String, Store> stores; // Справочник магазинов для расчёта расстояний
    // Текущая позиция грузовика (координаты)
    private double currentX;
    private double currentY;
    // Флаг занятости грузовика (выполняет ли он сейчас доставку)
    private boolean isBusy = false;
    // Очередь принятых заказов для планирования маршрута
    private List<PendingOrder> pendingOrders = new ArrayList<>();
    
    // Внутренний класс для хранения принятых заказов
    private static class PendingOrder {
        String storeId;
        Store store;
        List<String> productIds;
        List<Integer> quantities;
        double totalWeight;
        int totalQuantity;
        LocalTime requestedTime;
        
        PendingOrder(String storeId, Store store, List<String> productIds, List<Integer> quantities, 
                    double totalWeight, int totalQuantity) {
            this.storeId = storeId;
            this.store = store;
            this.productIds = productIds;
            this.quantities = quantities;
            this.totalWeight = totalWeight;
            this.totalQuantity = totalQuantity;
            this.requestedTime = LocalTime.now();
        }
    }

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            truck = (Truck) args[0];
            System.out.println("TruckAgent " + getLocalName() + " инициализирован: " + truck);
        } else {
            System.err.println("Ошибка инициализации TruckAgent: отсутствуют аргументы");
            doDelete();
            return;
        }

        // Инициализируем текущую позицию грузовика (на складе)
        currentX = truck.getStartX();
        currentY = truck.getStartY();

        // Загружаем справочник товаров для расчёта веса
        products = new HashMap<>();
        try {
            for (Product p : DataLoader.loadProducts("data/products.csv")) {
                products.put(p.getProductId(), p);
            }
            System.out.println("[" + getLocalName() + "] Загружено товаров: " + products.size());
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] Ошибка загрузки товаров: " + e.getMessage());
        }

        // Загружаем справочник магазинов для расчёта расстояний
        stores = new HashMap<>();
        try {
            for (Store s : DataLoader.loadStores("data/stores.csv")) {
                stores.put(s.getStoreId(), s);
            }
            System.out.println("[" + getLocalName() + "] Загружено магазинов: " + stores.size());
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] Ошибка загрузки магазинов: " + e.getMessage());
        }

        // Регистрируем в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("truck");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            jade.domain.DFService.register(this, dfd);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }

        // Поведение грузовика: принимает CFP от магазинов и договаривается о доставке
        addBehaviour(new TruckServiceBehaviour());
    }

    @Override
    protected void takeDown() {
        try {
            jade.domain.DFService.deregister(this);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("TruckAgent " + getLocalName() + " закончил работу");
    }

    /**
     * Поведение грузовика
     */
    private class TruckServiceBehaviour extends Behaviour {
        private static final int SERVICE_MINUTES = 30; // условная длительность одной доставки

        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.CFP),
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                            MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                    )
            );
            ACLMessage msg = receive(mt);
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение: " + msg.getContent());

                if (msg.getPerformative() == ACLMessage.CFP) {
                    handleCFP(msg);
                } else if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    handleAccept(msg);
                } else if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
                    handleReject(msg);
                }
            } else {
                block();
            }
        }

        /**
         * Обработка CFP от магазина: грузовик сам решает, может ли взять заказ.
         * Формат: DELIVERY_CFP:storeId:productId1:qty1:productId2:qty2:...
         */
        private void handleCFP(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length < 4 || !"DELIVERY_CFP".equals(parts[0])) {
                return;
            }
            
            String storeId = parts[1];
            
            // Парсим все товары из заказа
            List<String> productIds = new ArrayList<>();
            List<Integer> quantities = new ArrayList<>();
            double totalWeight = 0;
            int totalQuantity = 0;
            
            for (int i = 2; i < parts.length; i += 2) {
                if (i + 1 >= parts.length) break;
                String productId = parts[i];
                int qty = Integer.parseInt(parts[i + 1]);
                
                Product product = products.get(productId);
                double weight;
                if (product != null) {
                    weight = qty * product.getUnitWeight();
                } else {
                    System.err.println("[" + getLocalName() + "] Товар не найден: " + productId + ", используем вес по умолчанию 1.0");
                    weight = qty * 1.0; // fallback
                }
                
                productIds.add(productId);
                quantities.add(qty);
                totalWeight += weight;
                totalQuantity += qty;
            }
            
            if (productIds.isEmpty()) {
                System.err.println("[" + getLocalName() + "] Пустой заказ от магазина " + storeId);
                return;
            }

            // Проверяем, не занят ли грузовик другой доставкой
            if (isBusy) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("BUSY");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: грузовик занят другой доставкой");
                return;
            }

            // Проверяем грузоподъёмность для всех товаров вместе (учитывая текущую загрузку)
            if (!truck.hasCapacity(totalWeight)) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_CAPACITY");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: нет грузоподъёмности (текущая загрузка: " + 
                        truck.getCurrentLoad() + ", требуется: " + totalWeight + ", вместимость: " + truck.getCapacity() + ")");
                return;
            }

            // Получаем информацию о магазине для расчёта расстояния
            Store store = stores.get(storeId);
            if (store == null) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("STORE_NOT_FOUND");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: магазин не найден: " + storeId);
                return;
            }

            // Временное окно грузовика: можно ли вписать новую доставку в своё расписание
            LocalTime availStart = truck.getAvailabilityStart();
            LocalTime availEnd = truck.getAvailabilityEnd();
            LocalTime nextFree = truck.getNextFreeTime();
            if (nextFree == null) {
                nextFree = availStart;
            }

            // Рассчитываем расстояние от текущей позиции до магазина
            double distanceToStore = DistanceCalculator.calculateDistance(
                    currentX, currentY, store.getX(), store.getY()
            );

            // Рассчитываем время в пути
            int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToStore);

            // Планируем время с учетом окна МАГАЗИНА, а не только грузовика
            // Минимальное время выезда - когда грузовик будет свободен
            LocalTime minDepartureTime = nextFree.isAfter(availStart) ? nextFree : availStart;
            
            // Рассчитываем время прибытия при выезде в минимальное время
            LocalTime arrivalTime = minDepartureTime.plusSeconds(travelTimeSeconds);
            
            // Проверяем окно магазина - прибытие должно быть в пределах окна магазина
            if (arrivalTime.isBefore(store.getTimeWindowStart())) {
                // Приедем раньше окна - ждем до начала окна магазина
                arrivalTime = store.getTimeWindowStart();
            } else if (arrivalTime.isAfter(store.getTimeWindowEnd())) {
                // Приедем позже окна - отказываемся
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("TIME_WINDOW_PASSED");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: временное окно магазина уже прошло (окно: " + 
                        store.getTimeWindowStart() + "-" + store.getTimeWindowEnd() + ", прибытие: " + arrivalTime + ")");
                return;
            }
            
            // Рассчитываем время выезда для прибытия в окно магазина
            LocalTime plannedStart = arrivalTime.minusSeconds(travelTimeSeconds);
            
            // Проверяем, что выезд не раньше доступности грузовика
            if (plannedStart.isBefore(availStart)) {
                // Не можем выехать раньше - отказываемся
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_TIME_WINDOW");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: невозможно вписать в окно грузовика");
                return;
            }

            // Рассчитываем время обслуживания (для всех товаров вместе)
            int serviceTimeSeconds = DistanceCalculator.calculateServiceTime() + (totalQuantity * 60);
            LocalTime plannedEnd = arrivalTime.plusSeconds(serviceTimeSeconds);
            
            // Проверяем, что обслуживание завершится до конца окна магазина
            if (plannedEnd.isAfter(store.getTimeWindowEnd())) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_TIME_WINDOW");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: обслуживание не вписывается в окно магазина (окно до: " + 
                        store.getTimeWindowEnd() + ", завершение: " + plannedEnd + ")");
                return;
            }

            // Проверяем, что все вписывается в окно доступности грузовика
            if (plannedEnd.isAfter(availEnd)) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_TIME_WINDOW");
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отказ: нет свободного окна во времени грузовика");
                return;
            }

            // Если есть и место, и время – предлагаем услугу,
            // указывая в предложении точные времена для этой доставки
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.PROPOSE);
            double estimatedCost = DistanceCalculator.calculateCost(distanceToStore * 2, truck.getCostPerKm()); // туда-обратно
            // Формат: OFFER:storeId:productId1:qty1:productId2:qty2:...:cost=...:departure=...:arrival=...:departureFromStore=...
            StringBuilder offerContent = new StringBuilder("OFFER:" + storeId);
            for (int i = 0; i < productIds.size(); i++) {
                offerContent.append(":").append(productIds.get(i)).append(":").append(quantities.get(i));
            }
            offerContent.append(":cost=").append(estimatedCost)
                       .append(":departure=").append(plannedStart)
                       .append(":arrival=").append(arrivalTime)
                       .append(":departureFromStore=").append(plannedEnd);
            reply.setContent(offerContent.toString());
            send(reply);
            System.out.println("[" + getLocalName() + "] → Отправлено предложение магазину " + storeId +
                    " (" + productIds.size() + " товаров, выезд: " + plannedStart + ", прибытие: " + arrivalTime + ", отправление: " + plannedEnd + ")");
        }

        /**
         * Магазин принял наше предложение – добавляем заказ в очередь.
         * Формат: DELIVERY_ACCEPTED:storeId:productId1:qty1:productId2:qty2:...
         */
        private void handleAccept(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length < 4 || !"DELIVERY_ACCEPTED".equals(parts[0])) {
                return;
            }
            
            String storeId = parts[1];
            Store store = stores.get(storeId);
            if (store == null) {
                System.err.println("[" + getLocalName() + "] Магазин не найден: " + storeId);
                return;
            }

            // Парсим все товары из заказа
            List<String> productIds = new ArrayList<>();
            List<Integer> quantities = new ArrayList<>();
            double totalWeight = 0;
            int totalQuantity = 0;
            
            for (int i = 2; i < parts.length; i += 2) {
                if (i + 1 >= parts.length) break;
                String productId = parts[i];
                int qty = Integer.parseInt(parts[i + 1]);
                
                Product product = products.get(productId);
                double weight;
                if (product != null) {
                    weight = qty * product.getUnitWeight();
                } else {
                    System.err.println("[" + getLocalName() + "] Товар не найден: " + productId + ", используем вес по умолчанию 1.0");
                    weight = qty * 1.0; // fallback
                }
                
                productIds.add(productId);
                quantities.add(qty);
                totalWeight += weight;
                totalQuantity += qty;
            }
            
            if (productIds.isEmpty()) {
                System.err.println("[" + getLocalName() + "] Пустой заказ от магазина " + storeId);
                return;
            }

            // Проверяем грузоподъёмность
            if (!truck.hasCapacity(totalWeight)) {
                System.err.println("[" + getLocalName() + "] Недостаточно грузоподъёмности для заказа от " + storeId);
                return;
            }

            // Проверяем, нет ли уже такого заказа в очереди (предотвращаем дубликаты)
            synchronized (pendingOrders) {
                boolean alreadyExists = false;
                for (PendingOrder existing : pendingOrders) {
                    if (existing.storeId.equals(storeId)) {
                        // Проверяем, есть ли совпадения по товарам
                        boolean sameProducts = true;
                        if (existing.productIds.size() != productIds.size()) {
                            sameProducts = false;
                        } else {
                            for (int i = 0; i < productIds.size(); i++) {
                                if (!existing.productIds.get(i).equals(productIds.get(i)) ||
                                    !existing.quantities.get(i).equals(quantities.get(i))) {
                                    sameProducts = false;
                                    break;
                                }
                            }
                        }
                        if (sameProducts) {
                            alreadyExists = true;
                            System.out.println("[" + getLocalName() + "] ⚠ Заказ от " + storeId + " уже есть в очереди, игнорирую дубликат");
                            break;
                        }
                    }
                }
                
                if (!alreadyExists) {
                    pendingOrders.add(new PendingOrder(storeId, store, productIds, quantities, totalWeight, totalQuantity));
                    System.out.println("[" + getLocalName() + "] ✓ Заказ от " + storeId + " добавлен в очередь (" + 
                            productIds.size() + " товаров, вес=" + totalWeight + "). Всего в очереди: " + pendingOrders.size());
                } else {
                    return; // Не добавляем дубликат и не запускаем планирование маршрута
                }
            }

            // Если грузовик свободен, начинаем планирование маршрута
            if (!isBusy) {
                planAndExecuteRoute();
            }
        }
        
        /**
         * Планирует и выполняет маршрут из очереди заказов
         */
        private void planAndExecuteRoute() {
            if (isBusy) {
                return; // Уже выполняем маршрут
            }
            
            synchronized (pendingOrders) {
                if (pendingOrders.isEmpty()) {
                    return; // Нет заказов
                }
            }
            
            // Помечаем грузовик как занятый
            isBusy = true;
            
            // Планируем оптимальный маршрут из очереди
            List<PendingOrder> route = planOptimalRoute();
            
            if (route.isEmpty()) {
                isBusy = false;
                return;
            }
            
            System.out.println("\n[" + getLocalName() + "] === Начинаю выполнение маршрута (" + route.size() + " остановок) ===");
            
            // Выполняем маршрут
            executeRoute(route);
            
            // После завершения маршрута планируем следующий
            isBusy = false;
            planAndExecuteRoute();
        }
        
        /**
         * Планирует оптимальный маршрут из очереди заказов (жадный алгоритм - ближайший магазин)
         */
        private List<PendingOrder> planOptimalRoute() {
            List<PendingOrder> route = new ArrayList<>();
            double currentLoad = truck.getCurrentLoad();
            LocalTime currentTime = truck.getNextFreeTime();
            if (currentTime == null) {
                currentTime = truck.getAvailabilityStart();
            }
            double routeX = currentX;
            double routeY = currentY;
            
            // Копируем очередь для работы
            List<PendingOrder> availableOrders = new ArrayList<>();
            synchronized (pendingOrders) {
                availableOrders.addAll(pendingOrders);
            }
            
            // Жадный алгоритм: выбираем ближайший доступный магазин
            while (!availableOrders.isEmpty() && currentTime.isBefore(truck.getAvailabilityEnd())) {
                PendingOrder bestOrder = null;
                double minDistance = Double.MAX_VALUE;
                int bestIndex = -1;
                
                // Ищем ближайший доступный магазин
                for (int i = 0; i < availableOrders.size(); i++) {
                    PendingOrder order = availableOrders.get(i);
                    
                    // Проверяем грузоподъёмность
                    if (currentLoad + order.totalWeight > truck.getCapacity()) {
                        continue;
                    }
                    
                    // Рассчитываем расстояние
                    double distance = DistanceCalculator.calculateDistance(
                            routeX, routeY, order.store.getX(), order.store.getY());
                    
                    // Рассчитываем время прибытия
                    int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
                    LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                    
                    // Проверяем временное окно МАГАЗИНА
                    if (arrivalTime.isBefore(order.store.getTimeWindowStart())) {
                        // Приедем раньше окна - ждем до начала окна магазина
                        arrivalTime = order.store.getTimeWindowStart();
                    } else if (arrivalTime.isAfter(order.store.getTimeWindowEnd())) {
                        // Приедем позже окна - пропускаем этот заказ
                        continue;
                    }
                    
                    // Рассчитываем время обслуживания
                    int serviceTimeSeconds = DistanceCalculator.calculateServiceTime() + (order.totalQuantity * 60);
                    LocalTime departureTime = arrivalTime.plusSeconds(serviceTimeSeconds);
                    
                    // Проверяем, что обслуживание завершится до конца окна магазина
                    if (departureTime.isAfter(order.store.getTimeWindowEnd())) {
                        continue; // Не вписывается в окно магазина
                    }
                    
                    // Проверяем, не выходим ли за окно доступности грузовика
                    if (departureTime.isAfter(truck.getAvailabilityEnd())) {
                        continue;
                    }
                    
                    // Выбираем ближайший
                    if (distance < minDistance) {
                        minDistance = distance;
                        bestOrder = order;
                        bestIndex = i;
                    }
                }
                
                if (bestOrder == null) {
                    break; // Нет доступных заказов
                }
                
                // Добавляем в маршрут
                route.add(bestOrder);
                currentLoad += bestOrder.totalWeight;
                
                // Обновляем позицию и время
                int travelTimeSeconds = DistanceCalculator.calculateTravelTime(minDistance);
                LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                // Учитываем окно магазина
                if (arrivalTime.isBefore(bestOrder.store.getTimeWindowStart())) {
                    arrivalTime = bestOrder.store.getTimeWindowStart();
                } else if (arrivalTime.isAfter(bestOrder.store.getTimeWindowEnd())) {
                    // Это не должно произойти, так как мы уже проверили выше, но на всякий случай
                    arrivalTime = bestOrder.store.getTimeWindowStart();
                }
                int serviceTimeSeconds = DistanceCalculator.calculateServiceTime() + (bestOrder.totalQuantity * 60);
                LocalTime departureTime = arrivalTime.plusSeconds(serviceTimeSeconds);
                // Убеждаемся, что не выходим за окно магазина
                if (departureTime.isAfter(bestOrder.store.getTimeWindowEnd())) {
                    departureTime = bestOrder.store.getTimeWindowEnd();
                }
                currentTime = departureTime;
                routeX = bestOrder.store.getX();
                routeY = bestOrder.store.getY();
                
                // Удаляем из доступных
                availableOrders.remove(bestIndex);
            }
            
            // Удаляем заказы из основной очереди
            synchronized (pendingOrders) {
                pendingOrders.removeAll(route);
            }
            
            return route;
        }
        
        /**
         * Выполняет запланированный маршрут
         */
        private void executeRoute(List<PendingOrder> route) {
            LocalTime currentTime = truck.getNextFreeTime();
            if (currentTime == null) {
                currentTime = truck.getAvailabilityStart();
            }
            double routeX = currentX;
            double routeY = currentY;
            double currentLoad = truck.getCurrentLoad();
            
            for (PendingOrder order : route) {
                // Загружаем товары
                truck.addLoad(order.totalWeight);
                currentLoad += order.totalWeight;
                
                // Рассчитываем расстояние
                double distance = DistanceCalculator.calculateDistance(
                        routeX, routeY, order.store.getX(), order.store.getY());
                
                // Рассчитываем время в пути
                int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
                
                // Планируем прибытие с учетом окна МАГАЗИНА
                // Минимальное время прибытия - если выедем сейчас
                LocalTime minArrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                
                // Проверяем временное окно МАГАЗИНА
                LocalTime arrivalTime;
                if (minArrivalTime.isBefore(order.store.getTimeWindowStart())) {
                    // Приедем раньше окна - ждем до начала окна магазина
                    arrivalTime = order.store.getTimeWindowStart();
                } else if (minArrivalTime.isAfter(order.store.getTimeWindowEnd())) {
                    // Окно уже прошло, пропускаем этот заказ
                    truck.removeLoad(order.totalWeight);
                    continue;
                } else {
                    // Прибытие в пределах окна - используем рассчитанное время
                    arrivalTime = minArrivalTime;
                }
                
                // Рассчитываем время выезда для прибытия в окно магазина
                LocalTime departureTime = arrivalTime.minusSeconds(travelTimeSeconds);
                // Если выезд раньше текущего времени, используем текущее время и пересчитываем прибытие
                if (departureTime.isBefore(currentTime)) {
                    departureTime = currentTime;
                    arrivalTime = departureTime.plusSeconds(travelTimeSeconds);
                    // Проверяем, что новое время прибытия все еще в окне магазина
                    if (arrivalTime.isBefore(order.store.getTimeWindowStart())) {
                        // Все еще раньше окна - ждем до начала окна
                        arrivalTime = order.store.getTimeWindowStart();
                    } else if (arrivalTime.isAfter(order.store.getTimeWindowEnd())) {
                        // Теперь позже окна - пропускаем
                        truck.removeLoad(order.totalWeight);
                        continue;
                    }
                }
                
                // Время обслуживания
                int serviceTimeSeconds = DistanceCalculator.calculateServiceTime() + (order.totalQuantity * 60);
                LocalTime departureFromStore = arrivalTime.plusSeconds(serviceTimeSeconds);
                
                // Проверяем, что обслуживание завершится до конца окна магазина
                if (departureFromStore.isAfter(order.store.getTimeWindowEnd())) {
                    // Не вписывается в окно магазина - пропускаем
                    truck.removeLoad(order.totalWeight);
                    continue;
                }
                
                System.out.println("[" + getLocalName() + "] → Выезжаю в " + departureTime +
                        ", прибытие в " + order.storeId + " в " + arrivalTime +
                        " (окно магазина: " + order.store.getTimeWindowStart() + "-" + order.store.getTimeWindowEnd() + ")" +
                        ", отправление в " + departureFromStore);
                
                // Имитируем выполнение доставки
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Отправляем отчёты с расстоянием от предыдущей остановки
                sendDeliveryReports(order, departureTime, arrivalTime, departureFromStore, distance);
                
                // Разгружаем
                truck.removeLoad(order.totalWeight);
                currentLoad -= order.totalWeight;
                
                // Обновляем позицию и время
                routeX = order.store.getX();
                routeY = order.store.getY();
                currentTime = departureFromStore;
            }
            
            // Возвращаемся на склад
            double distanceToDepot = DistanceCalculator.calculateDistance(
                    routeX, routeY, truck.getStartX(), truck.getStartY());
            int returnTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToDepot);
            LocalTime returnTime = currentTime.plusSeconds(returnTimeSeconds);
            truck.setNextFreeTime(returnTime);
            currentX = truck.getStartX();
            currentY = truck.getStartY();
            
            System.out.println("[" + getLocalName() + "] ✓ Маршрут завершён, возвращение на склад в " + returnTime);
        }
        
        /**
         * Отправляет отчёты о доставке
         */
        private void sendDeliveryReports(PendingOrder order, LocalTime departureTime, 
                                        LocalTime arrivalTime, LocalTime departureFromStore, double distanceFromPrevious) {
            // Отправляем отчёт логгеру для каждого товара
            for (int i = 0; i < order.productIds.size(); i++) {
                String productId = order.productIds.get(i);
                int qty = order.quantities.get(i);
                
                ACLMessage logMsg = new ACLMessage(ACLMessage.INFORM);
                logMsg.addReceiver(new AID("logger", AID.ISLOCALNAME));
                // Формат времени для логгера: HH.mm (без секунд)
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH.mm");
                String departureStr = departureTime.format(fmt);
                String arrivalStr = arrivalTime.format(fmt);
                String departureFromStoreStr = departureFromStore.format(fmt);
                // Формат: DELIVERY_COMPLETE:storeId:productId:qty:truckId:departure:arrival:departureFromStore:distanceFromPrevious:prevX:prevY
                logMsg.setContent("DELIVERY_COMPLETE:" + order.storeId + ":" + productId + ":" + qty + ":" +
                        truck.getTruckId() + ":" + departureStr + ":" + arrivalStr + ":" + departureFromStoreStr + ":" +
                        String.format(Locale.US, "%.2f", distanceFromPrevious) + ":" + 
                        String.format(Locale.US, "%.2f", order.store.getX()) + ":" + 
                        String.format(Locale.US, "%.2f", order.store.getY()));
                send(logMsg);
            }
        }

        /**
         * Обработка отклонения предложения магазином.
         */
        private void handleReject(ACLMessage msg) {
            System.out.println("[" + getLocalName() + "] Предложение отклонено магазином: " + msg.getContent());
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    public Truck getTruck() {
        return truck;
    }
}
