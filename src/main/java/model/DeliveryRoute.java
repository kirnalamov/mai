package model;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Представляет маршрут доставки для одного автомобиля
 */
public class DeliveryRoute implements Serializable {
    private static final long serialVersionUID = 1L;

    private String routeId;
    private String truckId;
    private List<RouteStop> stops;
    private double totalDistance;
    private double totalCost;
    private LocalTime departureTime;
    private LocalTime estimatedReturnTime;
    // Временное окно доступности грузовика на платформе (из данных о машине)
    private LocalTime truckAvailabilityStart;
    private LocalTime truckAvailabilityEnd;

    public DeliveryRoute(String routeId, String truckId, LocalTime departureTime) {
        this.routeId = routeId;
        this.truckId = truckId;
        this.stops = new ArrayList<>();
        this.totalDistance = 0;
        this.totalCost = 0;
        this.departureTime = departureTime;
    }

    public static class RouteStop implements Serializable {
        private static final long serialVersionUID = 1L;

        private String storeId;
        private double x;
        private double y;
        private LocalTime arrivalTime;
        private LocalTime departureTime;
        private List<DeliveryItem> items;
        private double distanceFromPreviousStop;

        public RouteStop(String storeId, double x, double y) {
            this.storeId = storeId;
            this.x = x;
            this.y = y;
            this.items = new ArrayList<>();
        }

        // Getters and setters
        public String getStoreId() { return storeId; }
        public double getX() { return x; }
        public double getY() { return y; }
        public LocalTime getArrivalTime() { return arrivalTime; }
        public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }
        public LocalTime getDepartureTime() { return departureTime; }
        public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }
        public List<DeliveryItem> getItems() { return items; }
        public void addItem(DeliveryItem item) { this.items.add(item); }
        public double getDistanceFromPreviousStop() { return distanceFromPreviousStop; }
        public void setDistanceFromPreviousStop(double distance) { this.distanceFromPreviousStop = distance; }

        @Override
        public String toString() {
            return "Stop{store=" + storeId + ", arrival=" + arrivalTime + ", items=" + items.size() + '}';
        }
    }

    public static class DeliveryItem implements Serializable {
        private static final long serialVersionUID = 1L;

        private String productId;
        private int quantity;
        private double weight;

        public DeliveryItem(String productId, int quantity, double weight) {
            this.productId = productId;
            this.quantity = quantity;
            this.weight = weight;
        }

        public String getProductId() { return productId; }
        public int getQuantity() { return quantity; }
        public double getWeight() { return weight; }
    }

    public String getRouteId() { return routeId; }
    public String getTruckId() { return truckId; }
    public List<RouteStop> getStops() { return stops; }
    public double getTotalDistance() { return totalDistance; }
    public void setTotalDistance(double totalDistance) { this.totalDistance = totalDistance; }
    public double getTotalCost() { return totalCost; }
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
    public LocalTime getDepartureTime() { return departureTime; }
    public LocalTime getEstimatedReturnTime() { return estimatedReturnTime; }
    public void setEstimatedReturnTime(LocalTime estimatedReturnTime) { this.estimatedReturnTime = estimatedReturnTime; }
    public LocalTime getTruckAvailabilityStart() { return truckAvailabilityStart; }
    public void setTruckAvailabilityStart(LocalTime truckAvailabilityStart) { this.truckAvailabilityStart = truckAvailabilityStart; }
    public LocalTime getTruckAvailabilityEnd() { return truckAvailabilityEnd; }
    public void setTruckAvailabilityEnd(LocalTime truckAvailabilityEnd) { this.truckAvailabilityEnd = truckAvailabilityEnd; }

    public void addStop(RouteStop stop) {
        stops.add(stop);
    }

    @Override
    public String toString() {
        return "Route{" +
                "routeId='" + routeId + '\'' +
                ", truck='" + truckId + '\'' +
                ", stops=" + stops.size() +
                ", distance=" + String.format("%.2f", totalDistance) +
                ", cost=" + String.format("%.2f", totalCost) +
                '}';
    }
}
