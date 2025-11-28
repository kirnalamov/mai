package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Truck;
import java.time.LocalTime;

/**
 * Агент грузовика
 * Выполняет маршрут доставки товаров
 */
public class TruckAgent extends Agent {
    private Truck truck;
    private AID coordinatorAID;
    private String assignedRoute;

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

        // Ищем координатора
        findCoordinator();
        
        addBehaviour(new TruckServiceBehaviour());
    }
    
    /**
     * Поиск координатора через Directory Facilitator
     */
    private void findCoordinator() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("service");
        sd.setName("coordinator");
        template.addServices(sd);
        
        try {
            DFAgentDescription[] result = jade.domain.DFService.search(this, template);
            if (result.length > 0) {
                coordinatorAID = result[0].getName();
                System.out.println("[" + getLocalName() + "] Найден координатор: " + coordinatorAID.getName());
            } else {
                System.out.println("[" + getLocalName() + "] Координатор не найден, будет поиск позже");
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
        private boolean routeAssigned = false;
        private boolean routeExecuting = false;
        private int stopIndex = 0;

        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение: " + msg.getContent());

                if (msg.getPerformative() == ACLMessage.INFORM) {
                    if (msg.getContent().startsWith("ROUTE:")) {
                        handleRouteAssignment(msg);
                    }
                }
            } else {
                if (routeAssigned && !routeExecuting) {
                    // Начинаем выполнение маршрута
                    executeRoute();
                } else {
                    block();
                }
            }
        }

        private void handleRouteAssignment(ACLMessage msg) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                assignedRoute = parts[1];
                routeAssigned = true;
                System.out.println("[" + getLocalName() + "] ← Получен маршрут от координатора: " + assignedRoute);
                if (parts.length >= 3) {
                    System.out.println("[" + getLocalName() + "]   Количество остановок: " + parts[2].replace("stops=", ""));
                }

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.AGREE);
                reply.setContent("ROUTE_ACCEPTED:" + assignedRoute);
                send(reply);
                System.out.println("[" + getLocalName() + "] → Отправлено подтверждение координатору");
            }
        }

        private void executeRoute() {
            routeExecuting = true;
            System.out.println("\n[" + getLocalName() + "] === Начинаю выполнение маршрута: " + assignedRoute + " ===");
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

            // Отправляем отчет координатору
            if (coordinatorAID != null) {
                ACLMessage report = new ACLMessage(ACLMessage.INFORM);
                report.addReceiver(coordinatorAID);
                report.setContent("ROUTE_COMPLETED:" + assignedRoute + ":status=SUCCESS:truck=" + truck.getTruckId());
                send(report);
                System.out.println("[" + getLocalName() + "] → Отправлен отчет координатору о завершении маршрута");
            } else {
                System.err.println("[" + getLocalName() + "] ✗ Координатор не найден, отчет не отправлен");
            }

            // Завершаем работу
            block();
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    public Truck getTruck() {
        return truck;
    }

    public void setCoordinatorAID(AID coordinatorAID) {
        this.coordinatorAID = coordinatorAID;
    }
}
