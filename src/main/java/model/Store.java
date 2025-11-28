package model;

import java.io.Serializable;
import java.time.LocalTime;

/**
 * Представляет магазин с адресом, временным окном и потребностями
 */
public class Store implements Serializable {
    private static final long serialVersionUID = 1L;

    private String storeId;
    private double x;
    private double y;
    private LocalTime timeWindowStart;
    private LocalTime timeWindowEnd;
    private String name;

    public Store() {
    }

    public Store(String storeId, double x, double y, LocalTime start, LocalTime end) {
        this.storeId = storeId;
        this.x = x;
        this.y = y;
        this.timeWindowStart = start;
        this.timeWindowEnd = end;
        this.name = "Store_" + storeId;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public LocalTime getTimeWindowStart() {
        return timeWindowStart;
    }

    public void setTimeWindowStart(LocalTime timeWindowStart) {
        this.timeWindowStart = timeWindowStart;
    }

    public LocalTime getTimeWindowEnd() {
        return timeWindowEnd;
    }

    public void setTimeWindowEnd(LocalTime timeWindowEnd) {
        this.timeWindowEnd = timeWindowEnd;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Проверяет, находится ли время в пределах временного окна магазина
     */
    public boolean isWithinTimeWindow(LocalTime time) {
        if (timeWindowStart.isBefore(timeWindowEnd)) {
            return !time.isBefore(timeWindowStart) && !time.isAfter(timeWindowEnd);
        } else {
            // Окно переходит через полночь
            return !time.isBefore(timeWindowStart) || !time.isAfter(timeWindowEnd);
        }
    }

    @Override
    public String toString() {
        return "Store{" +
                "storeId='" + storeId + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", timeWindow=" + timeWindowStart + "-" + timeWindowEnd +
                '}';
    }
}
