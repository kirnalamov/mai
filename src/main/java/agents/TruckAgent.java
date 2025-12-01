package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Truck;
import java.time.LocalTime;

/**
 * Агент грузовика
 * Самостоятельно принимает решения о приеме заказов от магазинов и выполняет доставки
 */
public class TruckAgent extends Agent {
    private Truck truck;

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
        private boolean busy = false;

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
         */
        private void handleCFP(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length >= 4 && "DELIVERY_CFP".equals(parts[0])) {
                String storeId = parts[1];
                String productId = parts[2];
                int qty = Integer.parseInt(parts[3]);

                // Простое решение: если грузовик сейчас не занят и есть место – предлагает услугу
                double weight = qty * 1.0; // условный вес 1 единица = 1
                if (!busy && truck.hasCapacity(weight)) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    // В предложении можно кодировать оценку стоимости
                    double estimatedCost = truck.getCostPerKm() * 10; // упрощенная оценка
                    reply.setContent("OFFER:" + storeId + ":" + productId + ":" + qty + ":cost=" + estimatedCost);
                    send(reply);
                    System.out.println("[" + getLocalName() + "] → Отправлено предложение магазину " + storeId);
                } else {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("BUSY_OR_NO_CAPACITY");
                    send(reply);
                    System.out.println("[" + getLocalName() + "] → Отказ: нет возможности взять заказ");
                }
            }
        }

        /**
         * Магазин принял наше предложение – выполняем доставку.
         */
        private void handleAccept(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length >= 4 && "DELIVERY_ACCEPTED".equals(parts[0])) {
                String storeId = parts[1];
                String productId = parts[2];
                int qty = Integer.parseInt(parts[3]);

                busy = true;

                System.out.println("\n[" + getLocalName() + "] === Принят заказ магазина " + storeId +
                        " (товар=" + productId + ", qty=" + qty + ") ===");
            System.out.println("[" + getLocalName() + "] Выезжаю со склада в " + LocalTime.now());

            // Имитируем выполнение маршрута с задержками
            try {
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] → В пути к первой остановке...");
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] → Выполняю доставку...");
                Thread.sleep(1000);
                System.out.println("[" + getLocalName() + "] ✓ Маршрут выполнен успешно!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

                // Отправляем отчет напрямую магазину
                ACLMessage report = new ACLMessage(ACLMessage.INFORM);
                report.addReceiver(msg.getSender());
                report.setContent("DELIVERY_COMPLETE:store=" + storeId + ":truck=" + truck.getTruckId());
                send(report);
                System.out.println("[" + getLocalName() + "] → Отправлен отчет магазину о завершении доставки");

                // Отдельное сообщение логгеру для построения расписания
                ACLMessage logMsg = new ACLMessage(ACLMessage.INFORM);
                logMsg.addReceiver(new AID("logger", AID.ISLOCALNAME));
                logMsg.setContent("DELIVERY_COMPLETE:" + storeId + ":" + productId + ":" + qty + ":" + truck.getTruckId());
                send(logMsg);
                System.out.println("[" + getLocalName() + "] → Отправлен отчет логгеру о завершении доставки");

                busy = false;
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
