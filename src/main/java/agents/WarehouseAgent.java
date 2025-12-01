package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.DFService;
import jade.domain.FIPAException;
import model.Truck;
import model.Product;
import model.Store;
import model.DeliveryRoute;
import model.DeliveryRoute.RouteStop;
import model.DeliveryRoute.DeliveryItem;
import io.DataLoader;
import io.ScheduleWriter;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.*;

/**
 * Агент склада
 * Управляет запасами товаров и отправкой автомобилей
 */
public class WarehouseAgent extends Agent {
    private Map<String, Integer> inventory;  // product_id -> quantity
    private Map<String, Truck> truckCatalog;
    private Map<String, Product> productCatalog;
    private Map<String, Store> storeCatalog;
    private Map<String, DeliveryRoute> deliveryRoutes;

    @Override
    protected void setup() {
        System.out.println("WarehouseAgent " + getLocalName() + " инициализирован");

        inventory = new HashMap<>();
        truckCatalog = new HashMap<>();
        productCatalog = new HashMap<>();
        storeCatalog = new HashMap<>();
        deliveryRoutes = new LinkedHashMap<>();

        loadReferenceData();

        // Регистрируем в DF (Directory Facilitator)
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("warehouse");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        addBehaviour(new WarehouseServiceBehaviour());
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("WarehouseAgent " + getLocalName() + " закончил работу");
    }

    /**
     * Поведение склада: обработка запросов
     */
    private class WarehouseServiceBehaviour extends Behaviour {
        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение: " + msg.getContent());

                String content = msg.getContent();
                if (msg.getPerformative() == ACLMessage.REQUEST && content != null) {
                    if (content.startsWith("INIT_DATA:")) {
                        handleInitData(content, msg);
                    } else if (content.startsWith("INVENTORY_CHECK:")) {
                        handleInventoryCheck(content, msg);
                    } else if (content.startsWith("RESERVE:")) {
                        handleReservation(content, msg);
                    }
                } else if (msg.getPerformative() == ACLMessage.INFORM &&
                        content != null && content.startsWith("DELIVERY_COMPLETE:")) {
                    handleDeliveryLog(content);
                }
            } else {
                block();
            }
        }

        private void handleInitData(String content, ACLMessage msg) {
            String[] parts = content.split(":");
            if (parts.length == 3) {
                String productId = parts[1];
                int quantity = Integer.parseInt(parts[2]);
                inventory.put(productId, quantity);
                System.out.println("[" + getLocalName() + "] Инициализирован товар: " + productId + " = " + quantity);
            }
        }

        private void handleDeliveryLog(String content) {
            Map<String, String> data = parsePayload(content);
            String header = content.split(";", 2)[0];
            String routeId = header.substring(header.indexOf(':') + 1);
            String truckId = data.get("truckId");
            if (truckId == null) {
                System.err.println("[" + getLocalName() + "] Не указан truckId в сообщении: " + content);
                return;
            }

            Truck truck = truckCatalog.get(truckId);
            if (truck == null) {
                truck = new Truck(truckId, truckId, "ТС", data.getOrDefault("driver", "Экипаж"),
                        100, 0, 0, 0);
            }

            DeliveryRoute route = deliveryRoutes.get(truckId);
            if (route == null) {
                route = new DeliveryRoute("ROUTE_" + truckId, truck, LocalTime.now());
                deliveryRoutes.put(truckId, route);
            }

            try {
                String storeId = data.get("storeId");
                String storeName = data.getOrDefault("storeName", storeId);
                String address = data.getOrDefault("address", "-");
                String timeWindow = data.getOrDefault("timeWindow", "-");
                double x = Double.parseDouble(data.getOrDefault("x", "0"));
                double y = Double.parseDouble(data.getOrDefault("y", "0"));
                double distance = Double.parseDouble(data.getOrDefault("distance", "0"));
                double cost = Double.parseDouble(data.getOrDefault("cost", "0"));
                int qty = Integer.parseInt(data.getOrDefault("qty", "0"));
                double weight = Double.parseDouble(data.getOrDefault("weight", "0"));
                double unitWeight = productsUnitWeight(data.get("productId"), weight, qty);

                RouteStop stop = new RouteStop(storeId, storeName, address, timeWindow, x, y);
                stop.setDistanceFromPreviousStop(distance);
                if (data.containsKey("arrival")) {
                    stop.setArrivalTime(LocalTime.parse(data.get("arrival")));
                }
                if (data.containsKey("departure")) {
                    stop.setDepartureTime(LocalTime.parse(data.get("departure")));
                }

                DeliveryItem item = new DeliveryItem(
                        data.get("productId"),
                        data.getOrDefault("productName", data.get("productId")),
                        qty,
                        unitWeight,
                        weight
                );
                stop.addItem(item);
                route.addStop(stop);
                route.setTotalDistance(route.getTotalDistance() + distance * 2);
                route.setTotalCost(route.getTotalCost() + cost);

                writeScheduleSnapshot();
            } catch (Exception e) {
                System.err.println("[" + getLocalName() + "] Ошибка обработки доставки: " + e.getMessage());
            }
        }

        private double productsUnitWeight(String productId, double weight, int qty) {
            Product product = productCatalog.get(productId);
            if (product != null) {
                return product.getUnitWeight();
            }
            if (qty > 0) {
                return weight / qty;
            }
            return 0;
        }

        private Map<String, String> parsePayload(String content) {
            Map<String, String> map = new HashMap<>();
            String[] parts = content.split(";");
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0], kv[1]);
                }
            }
            return map;
        }

        private void writeScheduleSnapshot() {
            List<DeliveryRoute> routes = new ArrayList<>(deliveryRoutes.values());
            File outputDir = new File("output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            try {
                ScheduleWriter.writeScheduleToCSV("output/schedule.csv", routes);
                ScheduleWriter.writeScheduleToExcel("output/schedule.xlsx", routes);
                System.out.println("[" + getLocalName() + "] Расписание обновлено: " + routes.size() + " маршрутов");
            } catch (IOException e) {
                System.err.println("[" + getLocalName() + "] Ошибка сохранения расписания: " + e.getMessage());
            }
        }

        private void handleInventoryCheck(String content, ACLMessage msg) {
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                String productId = parts[1];
                int available = inventory.getOrDefault(productId, 0);

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.INFORM);
                reply.setContent("INVENTORY:" + productId + ":" + available);
                send(reply);
                System.out.println("[" + getLocalName() + "] Отправлено количество товара: " + productId + " = " + available);
            }
        }

        private void handleReservation(String content, ACLMessage msg) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                String productId = parts[1];
                int quantity = Integer.parseInt(parts[2]);
                int available = inventory.getOrDefault(productId, 0);

                ACLMessage reply = msg.createReply();
                if (available >= quantity) {
                    inventory.put(productId, available - quantity);
                    reply.setPerformative(ACLMessage.AGREE);
                    reply.setContent("RESERVED:" + productId + ":" + quantity);
                    System.out.println("[" + getLocalName() + "] Зарезервирован товар: " + productId + " = " + quantity);
                } else {
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("INSUFFICIENT:" + productId + ":available=" + available);
                    System.out.println("[" + getLocalName() + "] Недостаточно товара: " + productId);
                }
                send(reply);
            }
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    private void loadReferenceData() {
        try {
            for (Product p : DataLoader.loadProducts("data/products.csv")) {
                productCatalog.put(p.getProductId(), p);
            }
            for (Truck t : DataLoader.loadTrucks("data/trucks.csv")) {
                truckCatalog.put(t.getTruckId(), t);
            }
            for (Store s : DataLoader.loadStores("data/stores.csv")) {
                storeCatalog.put(s.getStoreId(), s);
            }
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] Ошибка загрузки справочников: " + e.getMessage());
        }
    }
}
