package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Store;

/**
 * Агент магазина
 * Подает запросы на доставку товаров
 */
public class StoreAgent extends Agent {
    private Store store;
    private AID coordinatorAID;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            store = (Store) args[0];
            System.out.println("StoreAgent " + getLocalName() + " инициализирован: " + store);
        } else {
            System.err.println("Ошибка инициализации StoreAgent: отсутствуют аргументы");
            doDelete();
            return;
        }

        // Регистрируем в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("store");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            jade.domain.DFService.register(this, dfd);
        } catch (jade.domain.FIPAException fe) {
            fe.printStackTrace();
        }

        // Ищем координатора через DF
        findCoordinator();
        
        addBehaviour(new StoreServiceBehaviour());
        addBehaviour(new RequestDeliveryBehaviour());
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
        System.out.println("StoreAgent " + getLocalName() + " закончил работу");
    }

    /**
     * Поведение магазина: обработка входящих сообщений
     */
    private class StoreServiceBehaviour extends Behaviour {
        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение от " + msg.getSender().getName() + ": " + msg.getContent());

                if (msg.getPerformative() == ACLMessage.INFORM) {
                    // Уведомление о доставке
                    handleDeliveryNotification(msg);
                }
            } else {
                block();
            }
        }

        private void handleDeliveryNotification(ACLMessage msg) {
            String content = msg.getContent();
            System.out.println("[" + getLocalName() + "] ✓ Получено уведомление о доставке: " + content);

            if (content.contains("DELIVERY_COMPLETE")) {
                System.out.println("[" + getLocalName() + "] ✓✓✓ Доставка завершена успешно!");
            }
        }

        @Override
        public boolean done() {
            return false;
        }
    }
    
    /**
     * Поведение: отправка запроса на доставку координатору
     */
    private class RequestDeliveryBehaviour extends Behaviour {
        private boolean requestSent = false;
        
        @Override
        public void action() {
            if (!requestSent) {
                // Ждем немного, чтобы координатор успел запуститься
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Если координатор не найден, ищем снова
                if (coordinatorAID == null) {
                    findCoordinator();
                }
                
                if (coordinatorAID != null) {
                    sendDeliveryRequest();
                    requestSent = true;
                } else {
                    System.out.println("[" + getLocalName() + "] Координатор еще не найден, повторная попытка...");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                block();
            }
        }
        
        private void sendDeliveryRequest() {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(coordinatorAID);
            msg.setContent("DELIVERY_REQUEST:" + store.getStoreId() + ":store_ready");
            send(msg);
            System.out.println("[" + getLocalName() + "] → Отправлен запрос на доставку координатору: " + coordinatorAID.getName());
        }
        
        @Override
        public boolean done() {
            return requestSent;
        }
    }

    public Store getStore() {
        return store;
    }

    public void setCoordinatorAID(AID coordinatorAID) {
        this.coordinatorAID = coordinatorAID;
    }
}
