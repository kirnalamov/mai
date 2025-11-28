package model;

import java.io.Serializable;

/**
 * Представляет товар на складе
 */
public class Product implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private String name;
    private double unitWeight;
    private int currentStock;

    public Product() {
    }

    public Product(String productId, String name, double unitWeight) {
        this.productId = productId;
        this.name = name;
        this.unitWeight = unitWeight;
        this.currentStock = 0;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getUnitWeight() {
        return unitWeight;
    }

    public void setUnitWeight(double unitWeight) {
        this.unitWeight = unitWeight;
    }

    public int getCurrentStock() {
        return currentStock;
    }

    public void setCurrentStock(int currentStock) {
        this.currentStock = currentStock;
    }

    @Override
    public String toString() {
        return "Product{" +
                "productId='" + productId + '\'' +
                ", name='" + name + '\'' +
                ", unitWeight=" + unitWeight +
                ", currentStock=" + currentStock +
                '}';
    }
}
