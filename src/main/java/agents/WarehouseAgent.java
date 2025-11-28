package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Truck;

import java.util.*;

/**
 * Агент склада
 * Управляет запасами товаров и отправкой автомобилей
 */
public class WarehouseAgent extends Agent {
    private Map<String, Integer> inventory;  // product_id -> quantity
    private List<Truck> trucks;
    private AID coordinatorAID;

    @Override
    protected void setup() {
        System.out.println("WarehouseAgent " + getLocalName() + " инициализирован");

        inventory = new HashMap<>();
        trucks = new ArrayList<>();

        // Регистрируем в DF (Directory Facilitator)
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("warehouse");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            jade.domain.DFService.register(this, dfd);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }

        addBehaviour(new WarehouseServiceBehaviour());
    }

    @Override
    protected void takeDown() {
        try {
            jade.domain.DFService.deregister(this);
        } catch (jade.domain.FIPAException fe) {
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

                if (msg.getPerformative() == ACLMessage.REQUEST) {
                    String content = msg.getContent();

                    if (content.startsWith("INIT_DATA:")) {
                        // Инициализация данных
                        handleInitData(content, msg);
                    } else if (content.startsWith("INVENTORY_CHECK:")) {
                        // Проверка инвентаря
                        handleInventoryCheck(content, msg);
                    } else if (content.startsWith("RESERVE:")) {
                        // Бронирование товара
                        handleReservation(content, msg);
                    }
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

    public void setInventory(Map<String, Integer> inventory) {
        this.inventory = inventory;
    }

    public void setTrucks(List<Truck> trucks) {
        this.trucks = trucks;
    }

    public void setCoordinatorAID(AID coordinatorAID) {
        this.coordinatorAID = coordinatorAID;
    }
}
