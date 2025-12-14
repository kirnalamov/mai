package model;

import java.io.Serializable;
import java.time.LocalTime;

/**
 * Представляет грузовой автомобиль
 */
public class Truck implements Serializable {
    private static final long serialVersionUID = 1L;

    private String truckId;
    private double capacity;           // общая грузоподъёмность (вес/объём)
    private double costPerKm;          // стоимость километра пробега
    private double currentLoad;        // текущая загруженность
    private double startX;             // координата склада X
    private double startY;             // координата склада Y

    // Временное окно доступности грузовика (например, 08:00–18:00)
    private LocalTime availabilityStart;
    private LocalTime availabilityEnd;

    // Время, когда грузовик станет свободен для следующего заказа
    private LocalTime nextFreeTime;

    public Truck() {
    }

    public Truck(String truckId, double capacity, double costPerKm, double startX, double startY) {
        this.truckId = truckId;
        this.capacity = capacity;
        this.costPerKm = costPerKm;
        this.currentLoad = 0;
        this.startX = startX;
        this.startY = startY;
        // По умолчанию: доступен весь день, nextFreeTime = начало окна
        this.availabilityStart = LocalTime.of(8, 0);
        this.availabilityEnd = LocalTime.of(18, 0);
        this.nextFreeTime = this.availabilityStart;
    }

    public Truck(String truckId, double capacity, double costPerKm,
                 double startX, double startY,
                 LocalTime availabilityStart, LocalTime availabilityEnd) {
        this.truckId = truckId;
        this.capacity = capacity;
        this.costPerKm = costPerKm;
        this.currentLoad = 0;
        this.startX = startX;
        this.startY = startY;
        this.availabilityStart = availabilityStart;
        this.availabilityEnd = availabilityEnd;
        this.nextFreeTime = availabilityStart;
    }

    public String getTruckId() {
        return truckId;
    }

    public void setTruckId(String truckId) {
        this.truckId = truckId;
    }

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public double getCostPerKm() {
        return costPerKm;
    }

    public void setCostPerKm(double costPerKm) {
        this.costPerKm = costPerKm;
    }

    public double getCurrentLoad() {
        return currentLoad;
    }

    public void setCurrentLoad(double currentLoad) {
        this.currentLoad = currentLoad;
    }

    public double getStartX() {
        return startX;
    }

    public void setStartX(double startX) {
        this.startX = startX;
    }

    public double getStartY() {
        return startY;
    }

    public void setStartY(double startY) {
        this.startY = startY;
    }

    public LocalTime getAvailabilityStart() {
        return availabilityStart;
    }

    public void setAvailabilityStart(LocalTime availabilityStart) {
        this.availabilityStart = availabilityStart;
    }

    public LocalTime getAvailabilityEnd() {
        return availabilityEnd;
    }

    public void setAvailabilityEnd(LocalTime availabilityEnd) {
        this.availabilityEnd = availabilityEnd;
    }

    public LocalTime getNextFreeTime() {
        return nextFreeTime;
    }

    public void setNextFreeTime(LocalTime nextFreeTime) {
        this.nextFreeTime = nextFreeTime;
    }

    /**
     * Проверяет, достаточно ли свободного места для груза
     */
    public boolean hasCapacity(double weight) {
        return currentLoad + weight <= capacity;
    }

    /**
     * Добавляет груз
     */
    public void addLoad(double weight) {
        if (!hasCapacity(weight)) {
            throw new IllegalArgumentException("Недостаточно места в автомобиле!");
        }
        currentLoad += weight;
    }

    /**
     * Разгружает часть груза
     */
    public void removeLoad(double weight) {
        if (weight > currentLoad) {
            throw new IllegalArgumentException("Нельзя разгрузить больше, чем загружено!");
        }
        currentLoad -= weight;
    }

    @Override
    public String toString() {
        return "Truck{" +
                "truckId='" + truckId + '\'' +
                ", capacity=" + capacity +
                ", costPerKm=" + costPerKm +
                ", currentLoad=" + currentLoad +
                ", utilization=" + String.format("%.1f%%", (currentLoad / capacity * 100)) +
                ", availability=" + availabilityStart + "-" + availabilityEnd +
                ", nextFreeTime=" + nextFreeTime +
                '}';
    }
}
