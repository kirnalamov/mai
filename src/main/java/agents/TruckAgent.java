package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Truck;
import model.Product;
import io.DataLoader;
import util.DistanceCalculator;

import java.io.IOException;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Агент грузовика.
 * В децентрализованной схеме сам принимает решения по заявкам магазинов:
 * отвечает на CFP, оценивает стоимость и имитирует выполнение доставки.
 */
public class TruckAgent extends Agent {
    private Truck truck;
    private Map<String, Product> products;
    private AID warehouseAID;

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

        // Локальная загрузка каталога товаров
        products = new HashMap<>();
        try {
            for (Product p : DataLoader.loadProducts("data/products.csv")) {
                products.put(p.getProductId(), p);
            }
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] Ошибка загрузки товаров: " + e.getMessage());
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

        findWarehouse();
        addBehaviour(new TruckServiceBehaviour());
    }

    private void findWarehouse() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("service");
        sd.setName("warehouse");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = jade.domain.DFService.search(this, template);
            if (result.length > 0) {
                warehouseAID = result[0].getName();
                System.out.println("[" + getLocalName() + "] Найден склад для логирования: " + warehouseAID.getName());
            } else {
                System.out.println("[" + getLocalName() + "] Склад не найден, отчеты будут только магазину");
            }
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }
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
        private boolean busy = false;

        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение: " + msg.getContent());

                switch (msg.getPerformative()) {
                    case ACLMessage.CFP:
                        handleCFP(msg);
                        break;
                    case ACLMessage.ACCEPT_PROPOSAL:
                        handleAccept(msg);
                        break;
                    case ACLMessage.REJECT_PROPOSAL:
                        // Можно залогировать отказ, но логика проста
                        System.out.println("[" + getLocalName() + "] Предложение отклонено магазином");
                        break;
                    default:
                        break;
                }
            } else {
                block();
            }
        }

        /**
         * Обработка CFP от магазина:
         * CFP:REQ_x:storeId:x:y:window:productId:qty
         */
        private void handleCFP(ACLMessage msg) {
            String content = msg.getContent();
            if (content == null || !content.startsWith("CFP:")) {
                return;
            }
            String[] parts = content.split(":");
            if (parts.length < 8) {
                return;
            }

            String reqId = parts[1];
            String storeId = parts[2];
            double storeX = Double.parseDouble(parts[3]);
            double storeY = Double.parseDouble(parts[4]);
            String productId = parts[6];
            int qty = Integer.parseInt(parts[7]);

            Product product = products.get(productId);
            if (product == null) {
                System.out.println("[" + getLocalName() + "] Неизвестный товар " + productId + ", отправляю REFUSE");
                ACLMessage refuse = msg.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("REFUSE:" + reqId);
                send(refuse);
                return;
            }

            double weight = product.getUnitWeight() * qty;
            if (!truck.hasCapacity(weight) || busy) {
                ACLMessage refuse = msg.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("REFUSE:" + reqId);
                send(refuse);
                return;
            }

            // Рассчитываем расстояние и стоимость (от склада до магазина)
            double distanceToStore = DistanceCalculator.calculateDistance(
                    truck.getStartX(), truck.getStartY(), storeX, storeY);
            double roundTrip = distanceToStore * 2;
            double cost = DistanceCalculator.calculateCost(roundTrip, truck.getCostPerKm());

            ACLMessage propose = msg.createReply();
            propose.setPerformative(ACLMessage.PROPOSE);
            propose.setContent("PROPOSE:" + reqId + ":" + storeId + ":" + productId + ":" + qty + ":" + cost + ":" + distanceToStore);
            send(propose);
            System.out.println("[" + getLocalName() + "] → PROPOSE по заявке " + reqId +
                    " (стоимость " + String.format("%.2f", cost) + ")");
        }

        /**
         * Обработка ACCEPT_PROPOSAL:
         * DECISION:reqId;key=value;...
         */
        private void handleAccept(ACLMessage msg) {
            String content = msg.getContent();
            if (content == null || !content.startsWith("DECISION:")) {
                return;
            }

            Map<String, String> data = parsePayload(content);
            String header = content.split(";", 2)[0];
            String reqId = header.split(":")[1];
            System.out.println("\n[" + getLocalName() + "] === Принято решение участвовать в доставке: " +
                    reqId + " ===");
            busy = true;

            LocalTime depart = LocalTime.now();
            try {
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] → В пути к магазину...");
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] → Выполняю доставку...");
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] ✓ Доставка выполнена успешно!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LocalTime arrival = LocalTime.now();

            StringBuilder report = new StringBuilder("DELIVERY_COMPLETE:" + reqId);
            report.append(";truckId=").append(truck.getTruckId());
            report.append(";truckName=").append(truck.getDisplayName());
            report.append(";driver=").append(truck.getDriverName());

            for (Map.Entry<String, String> entry : data.entrySet()) {
                report.append(";").append(entry.getKey()).append("=").append(entry.getValue());
            }
            report.append(";departure=").append(depart);
            report.append(";arrival=").append(arrival);

            ACLMessage done = new ACLMessage(ACLMessage.INFORM);
            done.addReceiver(msg.getSender());
            done.setContent(report.toString());
            send(done);

            if (warehouseAID != null) {
                ACLMessage warehouseMsg = new ACLMessage(ACLMessage.INFORM);
                warehouseMsg.addReceiver(warehouseAID);
                warehouseMsg.setContent(report.toString());
                send(warehouseMsg);
            }

            busy = false;
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    public Truck getTruck() {
        return truck;
    }

    private Map<String, String> parsePayload(String content) {
        Map<String, String> data = new HashMap<>();
        String[] parts = content.split(";");
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            if (kv.length == 2) {
                data.put(kv[0], kv[1]);
            }
        }
        return data;
    }
}
