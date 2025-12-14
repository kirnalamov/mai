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
                            MessageTemplate.or(
                                    MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL),
                                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                            )
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
                } else if (msg.getPerformative() == ACLMessage.INFORM && 
                          msg.getContent() != null && msg.getContent().startsWith("TRUCK_SCHEDULE_UPDATED:")) {
                    handleTruckScheduleUpdate(msg);
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

            // Если грузовик не на базе, нужно учесть время возврата на базу и погрузку
            LocalTime timeAfterReturnToBase = nextFree;
            double currentPosX = currentX;
            double currentPosY = currentY;
            
            // Если грузовик не на базе, рассчитываем время возврата на базу
            if (currentX != truck.getStartX() || currentY != truck.getStartY()) {
                double distanceToBase = DistanceCalculator.calculateDistance(
                        currentX, currentY, truck.getStartX(), truck.getStartY()
                );
                int returnTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToBase);
                timeAfterReturnToBase = nextFree.plusSeconds(returnTimeSeconds);
                // Добавляем время погрузки на базе (10 минут)
                int loadingTimeSeconds = DistanceCalculator.calculateLoadingTime();
                timeAfterReturnToBase = timeAfterReturnToBase.plusSeconds(loadingTimeSeconds);
                currentPosX = truck.getStartX();
                currentPosY = truck.getStartY();
            }

            // Рассчитываем расстояние от базы (или текущей позиции, если уже на базе) до магазина
            double distanceToStore = DistanceCalculator.calculateDistance(
                    currentPosX, currentPosY, store.getX(), store.getY()
            );

            // Рассчитываем время в пути
            int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToStore);

            // Планируем время с учетом окна МАГАЗИНА
            // Минимальное время выезда - после возврата на базу и погрузки (или когда грузовик будет свободен, если уже на базе)
            LocalTime minDepartureTime = timeAfterReturnToBase.isAfter(availStart) ? timeAfterReturnToBase : availStart;
            
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

            // Рассчитываем время обслуживания (разгрузка зависит от количества товаров)
            int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(totalQuantity);
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
            
            // Рассчитываем стоимость: путь туда + обратный путь от магазина до базы * 0.7
            double distanceFromStoreToBase = DistanceCalculator.calculateDistance(
                    store.getX(), store.getY(), truck.getStartX(), truck.getStartY()
            );
            double estimatedCost = DistanceCalculator.calculateCostWithReturn(
                    distanceToStore, distanceFromStoreToBase, truck.getCostPerKm()
            );
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

            // Уведомляем другие грузовики и магазины об изменении расписания
            notifyOtherTrucks(storeId, totalWeight, totalQuantity);
            notifyStores(storeId, totalWeight, totalQuantity);

            // Если грузовик свободен, планируем маршрут с задержкой для сбора заказов
            if (!isBusy) {
                new Thread(() -> {
                    try {
                        // Задержка для сбора заказов (2 секунды)
                        Thread.sleep(2000);
                        planAndExecuteRoute();
                    } catch (Exception e) {
                        System.err.println("[" + getLocalName() + "] Ошибка при выполнении маршрута: " + e.getMessage());
                        e.printStackTrace();
                        isBusy = false; // Сбрасываем флаг занятости при ошибке
                    }
                }).start();
            }
        }
        
        /**
         * Уведомляет другие грузовики об изменении расписания
         */
        private void notifyOtherTrucks(String storeId, double totalWeight, int totalQuantity) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("service");
                sd.setName("truck");
                template.addServices(sd);

                DFAgentDescription[] result = jade.domain.DFService.search(TruckAgent.this, template);
                if (result.length == 0) {
                    return;
                }

                // Формат: TRUCK_SCHEDULE_UPDATED:truckId:storeId:weight:quantity
                String content = "TRUCK_SCHEDULE_UPDATED:" + truck.getTruckId() + ":" + storeId + ":" + totalWeight + ":" + totalQuantity;
                
                for (DFAgentDescription desc : result) {
                    AID truckAID = desc.getName();
                    // Не отправляем уведомление самому себе
                    if (!truckAID.equals(getAID())) {
                        ACLMessage notification = new ACLMessage(ACLMessage.INFORM);
                        notification.addReceiver(truckAID);
                        notification.setContent(content);
                        send(notification);
                    }
                }
                System.out.println("[" + getLocalName() + "] 📢 Уведомлены другие грузовики об изменении расписания (заказ от " + storeId + ")");
            } catch (Exception e) {
                System.err.println("[" + getLocalName() + "] Ошибка при уведомлении других грузовиков: " + e.getMessage());
            }
        }
        
        /**
         * Уведомляет магазины об изменении расписания грузовика
         */
        private void notifyStores(String acceptedStoreId, double totalWeight, int totalQuantity) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("service");
                sd.setName("store");
                template.addServices(sd);

                DFAgentDescription[] result = jade.domain.DFService.search(TruckAgent.this, template);
                if (result.length == 0) {
                    return;
                }

                // Формат: TRUCK_SCHEDULE_CHANGED:truckId:acceptedStoreId:weight:quantity:nextAvailableTime
                // Рассчитываем примерное время, когда грузовик снова будет доступен
                LocalTime nextAvailable = truck.getNextFreeTime();
                if (nextAvailable == null) {
                    nextAvailable = truck.getAvailabilityStart();
                }
                
                // Приблизительно оцениваем время выполнения заказа (время погрузки + путь + разгрузка)
                // Это будет уточнено при планировании маршрута
                int estimatedServiceTime = DistanceCalculator.calculateLoadingTime() + 
                                         DistanceCalculator.calculateServiceTime(totalQuantity);
                nextAvailable = nextAvailable.plusSeconds(estimatedServiceTime);
                
                String content = "TRUCK_SCHEDULE_CHANGED:" + truck.getTruckId() + ":" + acceptedStoreId + 
                               ":" + totalWeight + ":" + totalQuantity + ":" + nextAvailable.toString();
                
                for (DFAgentDescription desc : result) {
                    AID storeAID = desc.getName();
                    // Не уведомляем магазин, который принял заказ (он уже знает)
                    if (!storeAID.getLocalName().equals("store_" + acceptedStoreId)) {
                        ACLMessage notification = new ACLMessage(ACLMessage.INFORM);
                        notification.addReceiver(storeAID);
                        notification.setContent(content);
                        send(notification);
                    }
                }
                System.out.println("[" + getLocalName() + "] 📢 Уведомлены магазины об изменении расписания " +
                                 "(принят заказ от " + acceptedStoreId + ", следующая доступность: " + nextAvailable + ")");
            } catch (Exception e) {
                System.err.println("[" + getLocalName() + "] Ошибка при уведомлении магазинов: " + e.getMessage());
            }
        }
        
        /**
         * Обрабатывает уведомление об изменении расписания другого грузовика
         */
        private void handleTruckScheduleUpdate(ACLMessage msg) {
            String content = msg.getContent();
            // Формат: TRUCK_SCHEDULE_UPDATED:truckId:storeId:weight:quantity
            String[] parts = content.split(":");
            if (parts.length < 5 || !"TRUCK_SCHEDULE_UPDATED".equals(parts[0])) {
                return;
            }
            
            String otherTruckId = parts[1];
            String storeId = parts[2];
            
            System.out.println("[" + getLocalName() + "] 📨 Получено уведомление от " + otherTruckId + 
                    " об изменении расписания (заказ от " + storeId + ")");
            
            // Пересчитываем предложения для магазинов, которые ждут ответа
            // Это будет сделано автоматически при следующем CFP от магазина
            // Но можно также отправить обновленное предложение, если у нас есть активные предложения
        }
        
        /**
         * Планирует и выполняет маршрут из очереди заказов
         */
        private void planAndExecuteRoute() {
            if (isBusy) {
                System.out.println("[" + getLocalName() + "] Грузовик занят, пропускаю планирование маршрута");
                return; // Уже выполняем маршрут
            }
            
            synchronized (pendingOrders) {
                if (pendingOrders.isEmpty()) {
                    System.out.println("[" + getLocalName() + "] Нет заказов в очереди");
                    return; // Нет заказов
                }
                System.out.println("[" + getLocalName() + "] Начинаю планирование маршрута. Заказов в очереди: " + pendingOrders.size());
            }
            
            // Помечаем грузовик как занятый
            isBusy = true;
            
            // Планируем оптимальный маршрут из очереди
            List<PendingOrder> route = planOptimalRoute();
            
            if (route.isEmpty()) {
                System.out.println("[" + getLocalName() + "] ⚠ Не удалось спланировать маршрут (возможно, все заказы не вписываются в временные окна)");
                isBusy = false;
                return;
            }
            
            System.out.println("\n[" + getLocalName() + "] === Начинаю выполнение маршрута (" + route.size() + " остановок) ===");
            
            // Выполняем маршрут
            List<PendingOrder> executedOrders = executeRoute(route);
            
            // Удаляем выполненные заказы из очереди
            synchronized (pendingOrders) {
                pendingOrders.removeAll(executedOrders);
                System.out.println("[" + getLocalName() + "] ✓ Выполнено заказов: " + executedOrders.size() + 
                        ", осталось в очереди: " + pendingOrders.size());
            }
            
            // После завершения маршрута и возврата на базу планируем следующий
            isBusy = false;
            
            // Проверяем, есть ли еще заказы в очереди
            synchronized (pendingOrders) {
                if (!pendingOrders.isEmpty()) {
                    System.out.println("[" + getLocalName() + "] 🔄 На базе. Есть новые заказы (" + pendingOrders.size() + 
                            "), планирую следующий маршрут...");
                    // Планируем следующий маршрут в отдельном потоке
                    new Thread(() -> {
                        try {
                            // Небольшая задержка для имитации загрузки на базе
                            Thread.sleep(100);
                            planAndExecuteRoute();
                        } catch (Exception e) {
                            System.err.println("[" + getLocalName() + "] Ошибка при планировании следующего маршрута: " + e.getMessage());
                            e.printStackTrace();
                            isBusy = false;
                        }
                    }).start();
                } else {
                    System.out.println("[" + getLocalName() + "] ✅ Все заказы выполнены. Ожидаю новые заказы на базе.");
                }
            }
        }
        
    // Коэффициент веса для стоимости (0.0 - только время, 1.0 - только стоимость)
    // 0.1 означает 10% веса на стоимость, 90% на время доставки (время имеет больший вес)
    private static final double COST_WEIGHT = 0.1;
    private static final double TIME_WEIGHT = 1.0 - COST_WEIGHT;
    // Коэффициент для увеличения веса времени доставки
    private static final double TIME_MULTIPLIER = 2.0;
    
        /**
         * Планирует оптимальный маршрут из очереди заказов с учетом стоимости и времени доставки
         */
        private List<PendingOrder> planOptimalRoute() {
            List<PendingOrder> route = new ArrayList<>();
            double currentLoad = truck.getCurrentLoad();
            LocalTime currentTime = truck.getNextFreeTime();
            if (currentTime == null) {
                currentTime = truck.getAvailabilityStart();
            }
            
            // Если грузовик не на базе, нужно учесть время возврата на базу и погрузку
            double routeX = currentX;
            double routeY = currentY;
            if (currentX != truck.getStartX() || currentY != truck.getStartY()) {
                // Грузовик не на базе - возвращаемся на базу и загружаем товары
                double distanceToBase = DistanceCalculator.calculateDistance(
                        currentX, currentY, truck.getStartX(), truck.getStartY()
                );
                int returnTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToBase);
                currentTime = currentTime.plusSeconds(returnTimeSeconds);
                // Добавляем время погрузки на базе (10 минут)
                int loadingTimeSeconds = DistanceCalculator.calculateLoadingTime();
                currentTime = currentTime.plusSeconds(loadingTimeSeconds);
                routeX = truck.getStartX();
                routeY = truck.getStartY();
            } else {
                // Грузовик на базе - добавляем время погрузки (10 минут)
                int loadingTimeSeconds = DistanceCalculator.calculateLoadingTime();
                currentTime = currentTime.plusSeconds(loadingTimeSeconds);
            }
            
            // Копируем очередь для работы
            List<PendingOrder> availableOrders = new ArrayList<>();
            synchronized (pendingOrders) {
                availableOrders.addAll(pendingOrders);
            }
            
            // Оптимизация с учетом стоимости и времени доставки
            while (!availableOrders.isEmpty() && currentTime.isBefore(truck.getAvailabilityEnd())) {
                PendingOrder bestOrder = null;
                double bestScore = Double.MAX_VALUE;
                int bestIndex = -1;
                
                // Первый проход: находим максимальные значения для нормализации
                double maxCost = 0;
                long maxTimeSeconds = 0;
                
                for (PendingOrder order : availableOrders) {
                    if (currentLoad + order.totalWeight > truck.getCapacity()) {
                        continue;
                    }
                    
                    double distance = DistanceCalculator.calculateDistance(
                            routeX, routeY, order.store.getX(), order.store.getY());
                    // Стоимость: путь туда + обратный путь от магазина до базы * 0.7
                    double distanceFromStoreToBase = DistanceCalculator.calculateDistance(
                            order.store.getX(), order.store.getY(), truck.getStartX(), truck.getStartY()
                    );
                    double cost = DistanceCalculator.calculateCostWithReturn(distance, distanceFromStoreToBase, truck.getCostPerKm());
                    int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
                    LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                    
                    if (arrivalTime.isBefore(order.store.getTimeWindowStart())) {
                        long waitSeconds = java.time.Duration.between(arrivalTime, order.store.getTimeWindowStart()).getSeconds();
                        travelTimeSeconds += waitSeconds;
                    } else if (arrivalTime.isAfter(order.store.getTimeWindowEnd())) {
                        continue;
                    }
                    
                    int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(order.totalQuantity); // Разгрузка зависит от количества товаров
                    LocalTime departureTime = arrivalTime.plusSeconds(serviceTimeSeconds);
                    
                    if (departureTime.isAfter(order.store.getTimeWindowEnd()) || 
                        departureTime.isAfter(truck.getAvailabilityEnd())) {
                        continue;
                    }
                    
                    // Учитываем время разгрузки при расчете общего времени для нормализации
                    long totalTimeSeconds = travelTimeSeconds + serviceTimeSeconds;
                    
                    maxCost = Math.max(maxCost, cost);
                    maxTimeSeconds = Math.max(maxTimeSeconds, totalTimeSeconds);
                }
                
                // Второй проход: выбираем лучший заказ по комбинированному критерию
                for (int i = 0; i < availableOrders.size(); i++) {
                    PendingOrder order = availableOrders.get(i);
                    
                    // Проверяем грузоподъёмность
                    if (currentLoad + order.totalWeight > truck.getCapacity()) {
                        continue;
                    }
                    
                    // Рассчитываем расстояние и стоимость
                    double distance = DistanceCalculator.calculateDistance(
                            routeX, routeY, order.store.getX(), order.store.getY());
                    
                    // Ищем ближайший следующий заказ для цепочки (без возврата на базу)
                    double distanceFromStore = Double.MAX_VALUE;
                    for (PendingOrder nextOrder : availableOrders) {
                        if (nextOrder == order) continue;
                        if (currentLoad + order.totalWeight + nextOrder.totalWeight > truck.getCapacity()) continue;
                        
                        double distToNext = DistanceCalculator.calculateDistance(
                                order.store.getX(), order.store.getY(), 
                                nextOrder.store.getX(), nextOrder.store.getY()
                        );
                        if (distToNext < distanceFromStore) {
                            distanceFromStore = distToNext;
                        }
                    }
                    
                    // Если не нашли следующий заказ в цепочке, считаем возврат на базу
                    if (distanceFromStore == Double.MAX_VALUE) {
                        distanceFromStore = DistanceCalculator.calculateDistance(
                                order.store.getX(), order.store.getY(), 
                                truck.getStartX(), truck.getStartY()
                        );
                    }
                    
                    double cost = DistanceCalculator.calculateCostWithReturn(distance, distanceFromStore, truck.getCostPerKm());
                    
                    // Рассчитываем время прибытия
                    int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
                    LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                    
                    // Проверяем временное окно МАГАЗИНА
                    if (arrivalTime.isBefore(order.store.getTimeWindowStart())) {
                        // Приедем раньше окна - ждем до начала окна магазина
                        long waitSeconds = java.time.Duration.between(arrivalTime, order.store.getTimeWindowStart()).getSeconds();
                        travelTimeSeconds += waitSeconds;
                        arrivalTime = order.store.getTimeWindowStart();
                    } else if (arrivalTime.isAfter(order.store.getTimeWindowEnd())) {
                        // Приедем позже окна - пропускаем этот заказ
                        continue;
                    }
                    
                    // Рассчитываем время обслуживания (разгрузка зависит от количества товаров)
                    int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(order.totalQuantity);
                    LocalTime departureTime = arrivalTime.plusSeconds(serviceTimeSeconds);
                    
                    // Проверяем, что обслуживание завершится до конца окна магазина
                    if (departureTime.isAfter(order.store.getTimeWindowEnd())) {
                        continue; // Не вписывается в окно магазина
                    }
                    
                    // Проверяем, не выходим ли за окно доступности грузовика
                    if (departureTime.isAfter(truck.getAvailabilityEnd())) {
                        continue;
                    }
                    
                    // Учитываем время разгрузки при расчете общего времени для нормализации
                    long totalTimeSeconds = travelTimeSeconds + serviceTimeSeconds;
                    
                    // Нормализуем значения (избегаем деления на ноль)
                    double normalizedCost = maxCost > 0 ? cost / maxCost : 0;
                    double normalizedTime = maxTimeSeconds > 0 ? (double)totalTimeSeconds / maxTimeSeconds : 0;
                    
                    // Комбинированный score: меньше = лучше
                    // Время доставки домножается на коэффициент для увеличения веса
                    double score = COST_WEIGHT * normalizedCost + TIME_WEIGHT * normalizedTime * TIME_MULTIPLIER;
                    
                    if (score < bestScore) {
                        bestScore = score;
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
                
                // Обновляем позицию и время (цепочка заказов без возврата на базу)
                double distance = DistanceCalculator.calculateDistance(
                        routeX, routeY, bestOrder.store.getX(), bestOrder.store.getY());
                int travelTimeSeconds = DistanceCalculator.calculateTravelTime(distance);
                LocalTime arrivalTime = currentTime.plusSeconds(travelTimeSeconds);
                // Учитываем окно магазина
                if (arrivalTime.isBefore(bestOrder.store.getTimeWindowStart())) {
                    arrivalTime = bestOrder.store.getTimeWindowStart();
                } else if (arrivalTime.isAfter(bestOrder.store.getTimeWindowEnd())) {
                    // Это не должно произойти, так как мы уже проверили выше, но на всякий случай
                    arrivalTime = bestOrder.store.getTimeWindowStart();
                }
                int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(bestOrder.totalQuantity);
                LocalTime departureTime = arrivalTime.plusSeconds(serviceTimeSeconds);
                // Убеждаемся, что не выходим за окно магазина
                if (departureTime.isAfter(bestOrder.store.getTimeWindowEnd())) {
                    departureTime = bestOrder.store.getTimeWindowEnd();
                }
                currentTime = departureTime;
                routeX = bestOrder.store.getX();
                routeY = bestOrder.store.getY();
                
                System.out.println("[" + getLocalName() + "] 📦 Добавлен в цепочку маршрута: " + bestOrder.storeId + 
                        " (прибытие: " + arrivalTime + ", отправление: " + departureTime + 
                        ", текущая загрузка: " + currentLoad + "/" + truck.getCapacity() + ")");
                
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
         * @return список успешно выполненных заказов
         */
        private List<PendingOrder> executeRoute(List<PendingOrder> route) {
            LocalTime currentTime = truck.getNextFreeTime();
            if (currentTime == null) {
                currentTime = truck.getAvailabilityStart();
            }
            
            // Начинаем с базы - загружаем все товары для маршрута
            double routeX = currentX;
            double routeY = currentY;
            double totalRouteWeight = 0;
            for (PendingOrder order : route) {
                totalRouteWeight += order.totalWeight;
            }
            
            // Загружаем все товары на базе перед началом маршрута
            truck.addLoad(totalRouteWeight);
            double currentLoad = truck.getCurrentLoad();
            System.out.println("[" + getLocalName() + "] 📦 Загружено товаров на базе: " + totalRouteWeight + " т (всего в грузовике: " + currentLoad + " т)");
            
            List<PendingOrder> executedOrders = new ArrayList<>();
            
            for (PendingOrder order : route) {
                
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
                    System.out.println("[" + getLocalName() + "] ⚠ Пропускаю заказ от " + order.storeId + 
                            " - временное окно уже прошло (окно: " + order.store.getTimeWindowStart() + 
                            "-" + order.store.getTimeWindowEnd() + ", прибытие: " + minArrivalTime + ")");
                    truck.removeLoad(order.totalWeight);
                    // НЕ добавляем в executedOrders, чтобы заказ остался в очереди для следующей попытки
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
                        System.out.println("[" + getLocalName() + "] ⚠ Пропускаю заказ от " + order.storeId + 
                                " - временное окно уже прошло (окно: " + order.store.getTimeWindowStart() + 
                                "-" + order.store.getTimeWindowEnd() + ", прибытие: " + arrivalTime + ")");
                        truck.removeLoad(order.totalWeight);
                        // НЕ добавляем в executedOrders, чтобы заказ остался в очереди для следующей попытки
                        continue;
                    }
                }
                
                // Время обслуживания (разгрузка зависит от количества товаров)
                int serviceTimeSeconds = DistanceCalculator.calculateServiceTime(order.totalQuantity);
                LocalTime departureFromStore = arrivalTime.plusSeconds(serviceTimeSeconds);
                
                // Проверяем, что обслуживание завершится до конца окна магазина
                if (departureFromStore.isAfter(order.store.getTimeWindowEnd())) {
                    // Не вписывается в окно магазина - пропускаем
                    System.out.println("[" + getLocalName() + "] ⚠ Пропускаю заказ от " + order.storeId + 
                            " - обслуживание не вписывается в окно магазина (окно до: " + 
                            order.store.getTimeWindowEnd() + ", завершение: " + departureFromStore + ")");
                    truck.removeLoad(order.totalWeight);
                    // НЕ добавляем в executedOrders, чтобы заказ остался в очереди для следующей попытки
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
                
                // Добавляем заказ в список выполненных
                executedOrders.add(order);
            }
            
            // Возвращаемся на склад
            if (!executedOrders.isEmpty()) {
                double distanceToDepot = DistanceCalculator.calculateDistance(
                        routeX, routeY, truck.getStartX(), truck.getStartY());
                int returnTimeSeconds = DistanceCalculator.calculateTravelTime(distanceToDepot);
                LocalTime returnTime = currentTime.plusSeconds(returnTimeSeconds);
                truck.setNextFreeTime(returnTime);
                currentX = truck.getStartX();
                currentY = truck.getStartY();
                
                System.out.println("[" + getLocalName() + "] ✓ Маршрут завершён, возвращение на склад в " + returnTime);
            }
            
            return executedOrders;
        }
        
        /**
         * Отправляет отчёты о доставке
         */
        private void sendDeliveryReports(PendingOrder order, LocalTime departureTime, 
                                        LocalTime arrivalTime, LocalTime departureFromStore, double distanceFromPrevious) {
            System.out.println("[" + getLocalName() + "] 📤 Отправляю отчёты о доставке в ScheduleLogger для " + order.productIds.size() + " товаров");
            
            // Отправляем отчёт логгеру для каждого товара
            for (int i = 0; i < order.productIds.size(); i++) {
                String productId = order.productIds.get(i);
                int qty = order.quantities.get(i);
                
                ACLMessage logMsg = new ACLMessage(ACLMessage.INFORM);
                AID loggerAID = new AID("logger", AID.ISLOCALNAME);
                logMsg.addReceiver(loggerAID);
                
                // Формат времени для логгера: HH.mm (без секунд)
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH.mm");
                String departureStr = departureTime.format(fmt);
                String arrivalStr = arrivalTime.format(fmt);
                String departureFromStoreStr = departureFromStore.format(fmt);
                // Формат: DELIVERY_COMPLETE:storeId:productId:qty:truckId:departure:arrival:departureFromStore:distanceFromPrevious:prevX:prevY
                String content = "DELIVERY_COMPLETE:" + order.storeId + ":" + productId + ":" + qty + ":" +
                        truck.getTruckId() + ":" + departureStr + ":" + arrivalStr + ":" + departureFromStoreStr + ":" +
                        String.format(Locale.US, "%.2f", distanceFromPrevious) + ":" + 
                        String.format(Locale.US, "%.2f", order.store.getX()) + ":" + 
                        String.format(Locale.US, "%.2f", order.store.getY());
                logMsg.setContent(content);
                
                System.out.println("[" + getLocalName() + "] → Отправляю в logger (" + loggerAID.getName() + "): " + productId + " x" + qty);
                send(logMsg);
            }
            
            System.out.println("[" + getLocalName() + "] ✓ Все отчёты отправлены в ScheduleLogger");
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
