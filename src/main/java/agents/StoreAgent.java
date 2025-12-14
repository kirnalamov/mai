package agents;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import model.Store;
import model.DeliveryRequest;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Агент магазина
 * Представляет весь магазин со всеми его потребностями
 * Самостоятельно договаривается с грузовиками о доставке всех товаров
 */
public class StoreAgent extends Agent {
    private Store store;
    // Все потребности магазина (список товаров с количествами)
    private List<DeliveryRequest> demands;
    // Отслеживание выполненных доставок
    private Map<String, Integer> deliveredProducts = new HashMap<>(); // productId -> delivered quantity
    private Map<String, Integer> orderedProducts = new HashMap<>(); // productId -> ordered quantity (принято к доставке, но еще не доставлено)
    private boolean cfpSent = false;
    private boolean orderAccepted = false;
    private boolean waitingForDelivery = false; // Ожидаем доставку от принятого грузовика
    private String acceptedTruckId = null; // ID грузовика, которому отправлен ACCEPT
    private long lastCfpTime = 0; // Время последней отправки CFP
    private static final long CFP_RETRY_INTERVAL = 5000; // Интервал повторной отправки CFP (5 секунд)

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length >= 2) {
            store = (Store) args[0];
            @SuppressWarnings("unchecked")
            List<DeliveryRequest> demandsList = (List<DeliveryRequest>) args[1];
            this.demands = demandsList != null ? demandsList : new ArrayList<>();
            System.out.println("StoreAgent " + getLocalName() + " инициализирован: " + store);
            System.out.println("  Потребностей: " + demands.size());
            for (DeliveryRequest req : demands) {
                System.out.println("    - " + req.getProductId() + ": " + req.getQuantity() + " шт");
            }
        } else {
            System.err.println("Ошибка инициализации StoreAgent: отсутствуют аргументы (store, demands)");
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
            // Если ещё не рассылали CFP или не все товары доставлены — отправляем CFP
            if (!cfpSent && !orderAccepted) {
                sendCfpToTrucks();
                cfpSent = true;
            }

            // Если заказ не принят и прошло достаточно времени - повторяем отправку CFP
            // НО только если не ожидаем доставку от уже принятого грузовика
            if (!orderAccepted && cfpSent && !waitingForDelivery) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCfpTime > CFP_RETRY_INTERVAL) {
                    // Проверяем, есть ли не доставленные товары
                    boolean hasPending = false;
                    for (DeliveryRequest req : demands) {
                        int delivered = deliveredProducts.getOrDefault(req.getProductId(), 0);
                        if (delivered < req.getQuantity()) {
                            hasPending = true;
                            break;
                        }
                    }
                    if (hasPending) {
                        System.out.println("[" + getLocalName() + "] Заказ не принят, повторяю отправку CFP...");
                        sendCfpToTrucks();
                        lastCfpTime = currentTime;
                    }
                }
            }

            // Обрабатываем как предложения грузовиков, так и уведомления о доставке, и отказы
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.or(
                                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE),
                                    MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                            )
                    )
            );
            ACLMessage msg = receive(mt);
            if (msg != null) {
                System.out.println("[" + getLocalName() + "] Получено сообщение от " + msg.getSender().getName() + ": " + msg.getContent());

                if (msg.getPerformative() == ACLMessage.INFORM) {
                    // Уведомление о доставке
                    handleDeliveryNotification(msg);
                } else if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    handleProposal(msg);
                } else if (msg.getPerformative() == ACLMessage.REFUSE || 
                          msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
                    // Грузовик отклонил заказ - это нормально, попробуем другой грузовик
                    System.out.println("[" + getLocalName() + "] Грузовик отклонил заказ: " + msg.getContent());
                }
            } else {
                block();
            }
        }

        private void handleDeliveryNotification(ACLMessage msg) {
            String content = msg.getContent();
            System.out.println("[" + getLocalName() + "] ✓ Получено уведомление о доставке: " + content);

            if (content.contains("DELIVERY_COMPLETE")) {
                // Парсим информацию о доставленных товарах
                // Формат: DELIVERY_COMPLETE:storeId:productId:qty:truckId или
                // DELIVERY_COMPLETE:storeId:productId:qty:truckId:departureTime:arrivalTime:departureFromStore
                String[] parts = content.split(":");
                if (parts.length >= 4 && parts[1].equals(store.getStoreId())) {
                    try {
                        String productId = parts[2];
                        int qty = Integer.parseInt(parts[3]);
                        deliveredProducts.put(productId, deliveredProducts.getOrDefault(productId, 0) + qty);
                        // Уменьшаем счетчик заказанных товаров (товар доставлен)
                        int ordered = orderedProducts.getOrDefault(productId, 0);
                        if (ordered > 0) {
                            int newOrdered = Math.max(0, ordered - qty);
                            if (newOrdered > 0) {
                                orderedProducts.put(productId, newOrdered);
                            } else {
                                orderedProducts.remove(productId);
                            }
                        }
                        System.out.println("[" + getLocalName() + "] ✓ Доставлено: " + productId + " x" + qty);
                    } catch (NumberFormatException e) {
                        System.err.println("[" + getLocalName() + "] Ошибка парсинга количества: " + (parts.length > 3 ? parts[3] : "N/A"));
                    }
                }
                
                // Проверяем, все ли товары доставлены (учитываем и заказанные)
                boolean allDelivered = true;
                for (DeliveryRequest req : demands) {
                    int delivered = deliveredProducts.getOrDefault(req.getProductId(), 0);
                    int ordered = orderedProducts.getOrDefault(req.getProductId(), 0);
                    if (delivered + ordered < req.getQuantity()) {
                        allDelivered = false;
                        break;
                    }
                }
                
                if (allDelivered) {
                    System.out.println("[" + getLocalName() + "] ✓✓✓ Все товары доставлены! Магазин закрывает заявку.");
                    orderAccepted = true; // Больше не принимаем предложения
                    waitingForDelivery = false; // Снимаем блокировку
                    acceptedTruckId = null; // Сбрасываем ID принятого грузовика
                    cfpSent = true; // Устанавливаем, чтобы не отправлять CFP
                } else {
                    // Если не все товары доставлены, разрешаем повторную отправку CFP
                    // НО только после того, как получим уведомление о доставке
                    System.out.println("[" + getLocalName() + "] Не все товары доставлены. Получено уведомление о доставке, разрешаю повторную отправку CFP...");
                    cfpSent = false; // Разрешаем отправить CFP снова
                    waitingForDelivery = false; // Снимаем блокировку, чтобы можно было принять новое предложение
                    acceptedTruckId = null; // Сбрасываем ID принятого грузовика, чтобы можно было принять предложение от другого грузовика
                }
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
                // Формат: DELIVERY_CFP:storeId:productId1:qty1:productId2:qty2:...
                // Отправляем только не доставленные И не заказанные товары
                StringBuilder content = new StringBuilder("DELIVERY_CFP:" + store.getStoreId());
                int pendingCount = 0;
                for (DeliveryRequest req : demands) {
                    int delivered = deliveredProducts.getOrDefault(req.getProductId(), 0);
                    int ordered = orderedProducts.getOrDefault(req.getProductId(), 0);
                    int remaining = req.getQuantity() - delivered - ordered; // Учитываем и доставленные, и заказанные
                    if (remaining > 0) {
                        content.append(":").append(req.getProductId()).append(":").append(remaining);
                        pendingCount++;
                    }
                }
                
                if (pendingCount > 0) {
                    cfp.setContent(content.toString());
                    send(cfp);
                    lastCfpTime = System.currentTimeMillis();
                    System.out.println("[" + getLocalName() + "] → Отправлен CFP всем грузовикам (" + pendingCount + " товаров осталось)");
                } else {
                    System.out.println("[" + getLocalName() + "] Все товары доставлены, CFP не отправляется");
                    orderAccepted = true;
                }
            } catch (jade.domain.FIPAException e) {
                e.printStackTrace();
            }
        }

        /**
         * Обработка предложения от грузовика.
         * Принимаем первое подходящее и отклоняем остальные.
         */
        private void handleProposal(ACLMessage msg) {
            if (orderAccepted) {
                // Все товары уже доставлены
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("DELIVERY_REJECTED:" + store.getStoreId() + ":ALL_DELIVERED");
                send(reject);
                System.out.println("[" + getLocalName() + "] → Отклонено предложение - все товары уже доставлены");
                return;
            }
            
            // Если уже ожидаем доставку от другого грузовика - отклоняем это предложение
            if (waitingForDelivery) {
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("DELIVERY_REJECTED:" + store.getStoreId() + ":ALREADY_ACCEPTED");
                send(reject);
                System.out.println("[" + getLocalName() + "] → Отклонено предложение - уже принято предложение от другого грузовика");
                return;
            }
            
            // Проверяем, есть ли не доставленные товары (учитываем и заказанные)
            boolean hasPending = false;
            for (DeliveryRequest req : demands) {
                int delivered = deliveredProducts.getOrDefault(req.getProductId(), 0);
                int ordered = orderedProducts.getOrDefault(req.getProductId(), 0);
                if (delivered + ordered < req.getQuantity()) {
                    hasPending = true;
                    break;
                }
            }
            
            if (!hasPending) {
                orderAccepted = true;
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("DELIVERY_REJECTED:" + store.getStoreId() + ":ALL_DELIVERED");
                send(reject);
                return;
            }
            
            // Принимаем предложение, отправляем только не доставленные И не заказанные товары
            // ВАЖНО: проверяем еще раз перед отправкой ACCEPT, что товары действительно не доставлены и не заказаны
            ACLMessage accept = msg.createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            // Формат: DELIVERY_ACCEPTED:storeId:productId1:qty1:productId2:qty2:...
            StringBuilder content = new StringBuilder("DELIVERY_ACCEPTED:" + store.getStoreId());
            int pendingCount = 0;
            Map<String, Integer> newOrdered = new HashMap<>(); // Товары, которые мы сейчас заказываем
            for (DeliveryRequest req : demands) {
                int delivered = deliveredProducts.getOrDefault(req.getProductId(), 0);
                int ordered = orderedProducts.getOrDefault(req.getProductId(), 0);
                int remaining = req.getQuantity() - delivered - ordered; // Учитываем и доставленные, и заказанные
                if (remaining > 0) {
                    content.append(":").append(req.getProductId()).append(":").append(remaining);
                    newOrdered.put(req.getProductId(), remaining); // Запоминаем, что заказываем
                    pendingCount++;
                }
            }
            
            if (pendingCount > 0) {
                String truckId = msg.getSender().getLocalName();
                // Проверяем, не отправляли ли мы уже ACCEPT этому грузовику
                if (waitingForDelivery && acceptedTruckId != null && acceptedTruckId.equals(truckId)) {
                    System.out.println("[" + getLocalName() + "] ⚠ Уже отправлен ACCEPT грузовику " + truckId + ", игнорирую повторное предложение");
                    return; // Игнорируем повторное предложение от того же грузовика
                }
                
                accept.setContent(content.toString());
                send(accept);
                // Обновляем счетчик заказанных товаров
                for (Map.Entry<String, Integer> entry : newOrdered.entrySet()) {
                    orderedProducts.put(entry.getKey(), orderedProducts.getOrDefault(entry.getKey(), 0) + entry.getValue());
                }
                waitingForDelivery = true; // Блокируем принятие других предложений до получения уведомления о доставке
                acceptedTruckId = truckId; // Запоминаем, какому грузовику отправили ACCEPT
                cfpSent = false; // Сбрасываем флаг, чтобы не отправлять CFP пока ждем доставку
                System.out.println("[" + getLocalName() + "] → Принято предложение грузовика " + truckId + 
                        " (" + pendingCount + " товаров). Ожидаю доставку...");
            } else {
                // Все товары уже доставлены (возможно, доставка произошла между проверкой и отправкой ACCEPT)
                orderAccepted = true;
                waitingForDelivery = false;
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("DELIVERY_REJECTED:" + store.getStoreId() + ":ALL_DELIVERED");
                send(reject);
                System.out.println("[" + getLocalName() + "] → Отклонено предложение - все товары уже доставлены (проверка перед отправкой ACCEPT)");
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
