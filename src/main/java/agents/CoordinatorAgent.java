package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.*;
import planning.RoutePlanningEngine;
import io.DataLoader;
import io.ScheduleWriter;

import java.io.IOException;
import java.util.*;

/**
 * Координирующий агент
 * Собирает запросы на доставку, планирует маршруты, координирует грузовики
 */
public class CoordinatorAgent extends Agent {
    private Map<String, Store> stores;
    private Map<String, Product> products;
    private List<Truck> trucks;
    private Map<String, List<DeliveryRequest>> demands;
    private List<DeliveryRoute> plannedRoutes;

    @Override
    protected void setup() {
        System.out.println("CoordinatorAgent " + getLocalName() + " инициализирован");

        stores = new HashMap<>();
        products = new HashMap<>();
        trucks = new ArrayList<>();
        demands = new HashMap<>();
        plannedRoutes = new ArrayList<>();

        // Регистрируем в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("coordinator");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            jade.domain.DFService.register(this, dfd);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }

        addBehaviour(new CoordinationBehaviour());
    }

    @Override
    protected void takeDown() {
        try {
            jade.domain.DFService.deregister(this);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("CoordinatorAgent " + getLocalName() + " закончил работу");
    }

    /**
     * Поведение координатора
     */
    private class CoordinationBehaviour extends Behaviour {
        private boolean initialized = false;
        private boolean routesPlanned = false;
        private Set<String> receivedStoreRequests = new HashSet<>();
        private Set<String> completedRoutes = new HashSet<>();
        private int expectedStores = 0;

        @Override
        public void action() {
            if (!initialized) {
                initializeData();
                expectedStores = stores.size();
                System.out.println("[" + getLocalName() + "] Ожидаю запросы от " + expectedStores + " магазинов...");
                initialized = true;
                return;
            }

            ACLMessage msg = receive();
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] ← Получено сообщение от " + msg.getSender().getName() + 
                                  " (тип: " + getPerformativeName(msg.getPerformative()) + ")");

                if (msg.getPerformative() == ACLMessage.INFORM) {
                    String content = msg.getContent();
                    if (content != null && content.startsWith("DELIVERY_REQUEST")) {
                        handleDeliveryRequest(msg);
                        // Проверяем, получили ли все запросы
                        if (!routesPlanned && receivedStoreRequests.size() >= expectedStores) {
                            System.out.println("[" + getLocalName() + "] ✓ Получены запросы от всех магазинов (" + 
                                            receivedStoreRequests.size() + "), начинаю планирование...");
                            planDeliveryRoutes();
                            routesPlanned = true;
                        }
                    } else if (content != null && content.startsWith("ROUTE_COMPLETED")) {
                        handleRouteCompleted(msg);
                        // Проверяем, завершены ли все маршруты
                        if (completedRoutes.size() >= plannedRoutes.size() && plannedRoutes.size() > 0) {
                            System.out.println("\n[" + getLocalName() + "] ✓✓✓ Все маршруты выполнены!");
                            System.out.println("[" + getLocalName() + "] Расписание сохранено в output/schedule.csv и output/schedule.xlsx");
                            System.out.println("[" + getLocalName() + "] Завершаю работу...");
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            doDelete(); // Завершаем работу координатора
                        }
                    }
                } else if (msg.getPerformative() == ACLMessage.AGREE) {
                    System.out.println("[" + getLocalName() + "] ✓ Грузовик принял маршрут: " + msg.getContent());
                }
            } else {
                block();
            }
        }

        private void initializeData() {
            // Загрузка данных из файлов
            try {
                products = loadProducts();
                stores = loadStores();
                trucks = loadTrucks();
                demands = loadDemands();

                System.out.println("[" + getLocalName() + "] Данные загружены:");
                System.out.println("  - Товаров: " + products.size());
                System.out.println("  - Магазинов: " + stores.size());
                System.out.println("  - Грузовиков: " + trucks.size());
                System.out.println("  - Заказов: " + demands.size());

                // Небольшая задержка для запуска других агентов
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

            } catch (IOException e) {
                System.err.println("Ошибка загрузки данных: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private Map<String, Product> loadProducts() throws IOException {
            Map<String, Product> map = new HashMap<>();
            List<Product> list = DataLoader.loadProducts("data/products.csv");
            for (Product p : list) {
                map.put(p.getProductId(), p);
            }
            return map;
        }

        private Map<String, Store> loadStores() throws IOException {
            Map<String, Store> map = new HashMap<>();
            List<Store> list = DataLoader.loadStores("data/stores.csv");
            for (Store s : list) {
                map.put(s.getStoreId(), s);
            }
            return map;
        }

        private List<Truck> loadTrucks() throws IOException {
            return DataLoader.loadTrucks("data/trucks.csv");
        }

        private Map<String, List<DeliveryRequest>> loadDemands() throws IOException {
            return DataLoader.loadDemands("data/stores.csv", products);
        }

        private void handleDeliveryRequest(ACLMessage msg) {
            String content = msg.getContent();
            System.out.println("[" + getLocalName() + "] ← Получен запрос от " + msg.getSender().getName() + ": " + content);
            
            if (content.startsWith("DELIVERY_REQUEST:")) {
                String[] parts = content.split(":");
                if (parts.length >= 2) {
                    String storeId = parts[1];
                    receivedStoreRequests.add(storeId);
                    System.out.println("[" + getLocalName() + "] ✓ Запрос от магазина " + storeId + " принят (" + 
                                     receivedStoreRequests.size() + "/" + expectedStores + ")");
                    
                    // Отправляем подтверждение
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.CONFIRM);
                    reply.setContent("REQUEST_ACCEPTED:" + storeId);
                    send(reply);
                }
            }
        }

        private void planDeliveryRoutes() {
            System.out.println("[" + getLocalName() + "] Начинается планирование маршрутов...");

            plannedRoutes = RoutePlanningEngine.planRoutes(trucks, stores, products, demands);

            System.out.println("[" + getLocalName() + "] Маршруты спланированы: " + plannedRoutes.size());
            for (DeliveryRoute route : plannedRoutes) {
                System.out.println("  - " + route);
            }

            // Сохраняем результаты
            try {
                ScheduleWriter.writeScheduleToCSV("output/schedule.csv", plannedRoutes);
                ScheduleWriter.writeScheduleToExcel("output/schedule.xlsx", plannedRoutes);
            } catch (IOException e) {
                System.err.println("Ошибка сохранения расписания: " + e.getMessage());
            }

            // Отправляем маршруты грузовикам
            sendRoutesToTrucks();
        }

        private void sendRoutesToTrucks() {
            System.out.println("\n[" + getLocalName() + "] === Отправка маршрутов грузовикам ===");
            
            for (DeliveryRoute route : plannedRoutes) {
                // Ищем грузовик через DF или по имени
                AID truckAID = findTruck(route.getTruckId());
                
                if (truckAID != null) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(truckAID);
                    msg.setContent("ROUTE:" + route.getRouteId() + ":stops=" + route.getStops().size());
                    send(msg);
                    System.out.println("[" + getLocalName() + "] → Отправлен маршрут " + route.getRouteId() + 
                                      " грузовику " + truckAID.getName() + " (" + route.getStops().size() + " остановок)");
                } else {
                    System.err.println("[" + getLocalName() + "] ✗ Грузовик " + route.getTruckId() + " не найден!");
                }
            }
        }
        
        private AID findTruck(String truckId) {
            // Грузовики теперь только на сервере, ищем по точному имени
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("service");
            sd.setName("truck");
            template.addServices(sd);
            
            try {
                DFAgentDescription[] result = jade.domain.DFService.search(CoordinatorAgent.this, template);
                for (DFAgentDescription desc : result) {
                    String localName = desc.getName().getLocalName();
                    // Ищем грузовик по ID (может быть TRUCK_001 или TRUCK_001_server)
                    if (localName.equals(truckId) || localName.startsWith(truckId + "_")) {
                        return desc.getName();
                    }
                }
            } catch (jade.domain.FIPAException fe) {
                // Игнорируем ошибки поиска
            }
            
            // Если не нашли через DF, пробуем просто по имени (грузовики на сервере)
            return new AID(truckId, AID.ISLOCALNAME);
        }
        
        private void handleRouteCompleted(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                String routeId = parts[1];
                completedRoutes.add(routeId);
            }
            System.out.println("[" + getLocalName() + "] ✓✓✓ Получен отчет о завершении маршрута: " + content);
            System.out.println("[" + getLocalName() + "]   От грузовика: " + msg.getSender().getName());
            System.out.println("[" + getLocalName() + "]   Выполнено маршрутов: " + completedRoutes.size() + "/" + plannedRoutes.size());
        }
        
        private String getPerformativeName(int performative) {
            switch (performative) {
                case ACLMessage.INFORM: return "INFORM";
                case ACLMessage.REQUEST: return "REQUEST";
                case ACLMessage.AGREE: return "AGREE";
                case ACLMessage.REFUSE: return "REFUSE";
                case ACLMessage.CONFIRM: return "CONFIRM";
                default: return "UNKNOWN(" + performative + ")";
            }
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    public Map<String, Store> getStores() {
        return stores;
    }

    public Map<String, Product> getProducts() {
        return products;
    }

    public List<Truck> getTrucks() {
        return trucks;
    }

    public List<DeliveryRoute> getPlannedRoutes() {
        return plannedRoutes;
    }
}
