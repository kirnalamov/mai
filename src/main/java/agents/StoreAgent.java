package agents;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Store;

/**
 * Агент магазина
 * Имеет собственные потребности и самостоятельно договаривается с грузовиками о доставке
 */
public class StoreAgent extends Agent {
    private Store store;
    // Условная потребность магазина (для простоты – одна партия товара)
    private String productId = "P001";
    private int quantity = 10;
    private boolean cfpSent = false;
    private boolean orderAccepted = false;

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

        // Регистрируемся в DF как равноправный сервис
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

        // Собственное поведение магазина:
        // 1) инициирует запросы к грузовикам;
        // 2) обрабатывает ответы и выбирает исполнителя;
        // 3) получает уведомление о доставке.
        addBehaviour(new StoreServiceBehaviour());
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
     * Поведение магазина: отправляет CFP и обрабатывает ответы/уведомления.
     */
    private class StoreServiceBehaviour extends Behaviour {
        @Override
        public void action() {
            // Если ещё не рассылали CFP — делаем это один раз
            if (!cfpSent) {
                sendCfpToTrucks();
                cfpSent = true;
            }

            // Обрабатываем как предложения грузовиков, так и уведомления о доставке
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            );
            ACLMessage msg = receive(mt);
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение от " + msg.getSender().getName() + ": " + msg.getContent());

                if (msg.getPerformative() == ACLMessage.INFORM) {
                    // Уведомление о доставке
                    handleDeliveryNotification(msg);
                } else if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    handleProposal(msg);
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

        /**
         * Отправка CFP всем доступным грузовикам через DF.
         */
        private void sendCfpToTrucks() {
            try {
                System.out.println("[" + getLocalName() + "] Поиск доступных грузовиков через DF...");
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("service");
                sd.setName("truck");
                template.addServices(sd);

                DFAgentDescription[] result = jade.domain.DFService.search(StoreAgent.this, template);
                if (result.length == 0) {
                    System.out.println("[" + getLocalName() + "] Грузовики не найдены, CFP не будет отправлен");
                    return;
                }

                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                for (DFAgentDescription desc : result) {
                    cfp.addReceiver(desc.getName());
                }
                cfp.setContent("DELIVERY_CFP:" + store.getStoreId() + ":" + productId + ":" + quantity);
                send(cfp);
                System.out.println("[" + getLocalName() + "] → Отправлен CFP всем грузовикам (товар=" + productId + ", qty=" + quantity + ")");
            } catch (jade.domain.FIPAException e) {
                e.printStackTrace();
            }
        }

        /**
         * Обработка предложения от грузовика.
         * Принимаем первое подходящее и отклоняем остальные.
         */
        private void handleProposal(ACLMessage msg) {
            if (!orderAccepted) {
                ACLMessage accept = msg.createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent("DELIVERY_ACCEPTED:" + store.getStoreId() + ":" + productId + ":" + quantity);
                send(accept);
                orderAccepted = true;
                System.out.println("[" + getLocalName() + "] → Принято предложение грузовика " + msg.getSender().getLocalName());
            } else {
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("DELIVERY_REJECTED:" + store.getStoreId());
                send(reject);
                System.out.println("[" + getLocalName() + "] → Отклонено дополнительное предложение от " + msg.getSender().getLocalName());
            }
        }

        @Override
        public boolean done() {
            return false;
        }
    }

    public Store getStore() {
        return store;
    }
}
