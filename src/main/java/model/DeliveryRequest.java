package model;

import java.io.Serializable;

/**
 * Представляет запрос на доставку товара в магазин
 */
public class DeliveryRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String requestId;
    private String storeId;
    private String productId;
    private int quantity;
    private double totalWeight;
    private DeliveryStatus status;

    public enum DeliveryStatus {
        PENDING, ACCEPTED, DELIVERED, FAILED
    }

    public DeliveryRequest() {
    }

    public DeliveryRequest(String requestId, String storeId, String productId, int quantity, double totalWeight) {
        this.requestId = requestId;
        this.storeId = storeId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalWeight = totalWeight;
        this.status = DeliveryStatus.PENDING;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(double totalWeight) {
        this.totalWeight = totalWeight;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "DeliveryRequest{" +
                "requestId='" + requestId + '\'' +
                ", storeId='" + storeId + '\'' +
                ", productId='" + productId + '\'' +
                ", quantity=" + quantity +
                ", status=" + status +
                '}';
    }
}
