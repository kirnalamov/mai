package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.DFService;
import jade.domain.FIPAException;
import model.Store;
import model.DeliveryRequest;
import model.Product;
import io.DataLoader;

import java.io.IOException;
import java.util.*;

/**
 * Агент магазина.
 * В децентрализованной схеме сам ведет переговоры с грузовиками
 * (Contract Net): рассылает CFP, получает PROPOSE/REFUSE и выбирает лучший вариант.
 */
public class StoreAgent extends Agent {
    private Store store;
    private Map<String, Product> products;
    private List<DeliveryRequest> demands;

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

        // Локальная загрузка справочников и потребностей конкретного магазина
        try {
            products = new HashMap<>();
            for (Product p : DataLoader.loadProducts("data/products.csv")) {
                products.put(p.getProductId(), p);
            }
            Map<String, List<DeliveryRequest>> allDemands =
                    DataLoader.loadDemands("data/stores.csv", products);
            demands = allDemands.getOrDefault(store.getStoreId(), new ArrayList<>());
            System.out.println("[" + getLocalName() + "] Локальные потребности: " + demands.size() + " позиций");
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] Ошибка загрузки данных: " + e.getMessage());
            demands = new ArrayList<>();
        }

        // Регистрируем в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setName("store");
        sd.setType("service");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        addBehaviour(new StoreServiceBehaviour());
        addBehaviour(new NegotiationBehaviour());
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
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
     * Поведение: переговоры с грузовиками (Contract Net)
     */
    private class NegotiationBehaviour extends Behaviour {
        private boolean started = false;
        private int currentIndex = 0;
        private final Map<String, ProposalSet> pendingProposals = new HashMap<>();

        @Override
        public void action() {
            if (!started) {
                started = true;
                System.out.println("[" + getLocalName() + "] Начинаю переговоры с грузовиками по " +
                        demands.size() + " заявкам");
            }

            // Обработка входящих предложений PROPOSE/REFUSE
            ACLMessage msg = receive();
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE ||
                        msg.getPerformative() == ACLMessage.REFUSE) {
                    handleProposalMessage(msg);
                }
            } else {
                block(200);
            }

            // Рассылаем CFP по очереди для всех заявок
            if (currentIndex < demands.size()) {
                DeliveryRequest req = demands.get(currentIndex);
                sendCFPForRequest(req);
                currentIndex++;
                block(500);
            } else {
                // Все CFP разосланы — принимаем решения по уже собранным предложениям
                for (ProposalSet set : pendingProposals.values()) {
                    if (!set.decided) {
                        decideForProposalSet(set);
                    }
                }
                block(500);
            }
        }
        
        private void sendCFPForRequest(DeliveryRequest req) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("service");
                sd.setName("truck");
                template.addServices(sd);

                DFAgentDescription[] result = DFService.search(StoreAgent.this, template);
                if (result.length == 0) {
                    System.out.println("[" + getLocalName() + "] Нет доступных грузовиков для заявки " +
                            req.getRequestId());
                    return;
                }

                String key = req.getRequestId();
                ProposalSet set = new ProposalSet(req);
                pendingProposals.put(key, set);

                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                for (DFAgentDescription d : result) {
                    cfp.addReceiver(d.getName());
                }
                String window = store.getTimeWindowStart() + "-" + store.getTimeWindowEnd();
                cfp.setContent("CFP:" + key + ":" + store.getStoreId() + ":" +
                        store.getX() + ":" + store.getY() + ":" +
                        window + ":" +
                        req.getProductId() + ":" + req.getQuantity());
                send(cfp);
                System.out.println("[" + getLocalName() + "] → CFP по заявке " + key +
                        " отправлен " + result.length + " грузовикам");

            } catch (FIPAException e) {
                e.printStackTrace();
            }
        }

        private void handleProposalMessage(ACLMessage msg) {
            String content = msg.getContent();
            if (content == null || (!content.startsWith("PROPOSE:") && !content.startsWith("REFUSE:"))) {
                return;
            }
            String[] parts = content.split(":");
            if (parts.length < 2) return;
            String reqId = parts[1];
            ProposalSet set = pendingProposals.get(reqId);
            if (set == null) return;

            if (msg.getPerformative() == ACLMessage.REFUSE) {
                set.refusals++;
                return;
            }

            // PROPOSE:REQ_x:storeId:productId:qty:cost:distance
            if (parts.length >= 7) {
                double cost = Double.parseDouble(parts[5]);
                double distance = Double.parseDouble(parts[6]);
                set.addProposal(msg.getSender(), cost, distance);
            }
        }

        private void decideForProposalSet(ProposalSet set) {
            if (set.decided) return;

            if (set.proposals.isEmpty()) {
                System.out.println("[" + getLocalName() + "] Нет подходящих предложений по заявке " +
                        set.request.getRequestId());
                set.decided = true;
                return;
            }

            Proposal best = null;
            for (Proposal p : set.proposals) {
                if (best == null || p.cost < best.cost) {
                    best = p;
                }
            }

            Product product = products.get(set.request.getProductId());
            double weight = set.request.getTotalWeight();
            StringBuilder decision = new StringBuilder("DECISION:" + set.request.getRequestId());
            decision.append(";storeId=").append(store.getStoreId());
            decision.append(";storeName=").append(store.getName());
            decision.append(";address=").append(store.getAddress());
            decision.append(";x=").append(store.getX());
            decision.append(";y=").append(store.getY());
            decision.append(";timeWindow=").append(store.getTimeWindowStart()).append("-")
                    .append(store.getTimeWindowEnd());
            decision.append(";productId=").append(set.request.getProductId());
            decision.append(";productName=").append(product != null ? product.getName() : set.request.getProductId());
            decision.append(";qty=").append(set.request.getQuantity());
            decision.append(";weight=").append(weight);
            decision.append(";distance=").append(best.distance);
            decision.append(";cost=").append(best.cost);

            // Отправляем ACCEPT выбранному грузовику, всем остальным REJECT
            for (Proposal p : set.proposals) {
                ACLMessage reply = new ACLMessage(
                        p == best ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL
                );
                reply.addReceiver(p.truck);
                reply.setContent(decision.toString());
                send(reply);
            }

            System.out.println("[" + getLocalName() + "] ✓ Выбран грузовик " +
                    best.truck.getLocalName() + " по заявке " + set.request.getRequestId() +
                    " (стоимость " + String.format("%.2f", best.cost) + ")");
            set.decided = true;
        }

        @Override
        public boolean done() {
            // Магазин теоретически может появляться и позже, поэтому не завершаем поведение
            return false;
        }
    }

    public Store getStore() {
        return store;
    }

    private static class ProposalSet {
        final DeliveryRequest request;
        final java.util.List<Proposal> proposals = new java.util.ArrayList<>();
        int refusals = 0;
        boolean decided = false;

        ProposalSet(DeliveryRequest request) {
            this.request = request;
        }

        void addProposal(AID truck, double cost, double distance) {
            proposals.add(new Proposal(truck, cost, distance));
        }
    }

    private static class Proposal {
        final AID truck;
        final double cost;
        final double distance;

        Proposal(AID truck, double cost, double distance) {
            this.truck = truck;
            this.cost = cost;
            this.distance = distance;
        }
    }
}
