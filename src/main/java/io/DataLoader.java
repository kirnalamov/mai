package io;

import model.Product;
import model.Store;
import model.Truck;
import model.DeliveryRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.time.LocalTime;
import java.util.*;

/**
 * Чтение входных данных из CSV файлов
 */
public class DataLoader {

    public static List<Product> loadProducts(String filename) throws IOException {
        List<Product> products = new ArrayList<>();
        
        try (Reader reader = new FileReader(filename)) {
            CSVFormat format = CSVFormat.DEFAULT.withFirstRecordAsHeader();
            CSVParser parser = new CSVParser(reader, format);

            for (CSVRecord record : parser) {
                String productId = record.get("product_id");
                String name = record.get("name");
                double unitWeight = Double.parseDouble(record.get("unit_weight"));

                products.add(new Product(productId, name, unitWeight));
            }
        }

        return products;
    }

    public static List<Store> loadStores(String filename) throws IOException {
        Map<String, Store> stores = new LinkedHashMap<>();
        
        try (Reader reader = new FileReader(filename)) {
            CSVFormat format = CSVFormat.DEFAULT.withFirstRecordAsHeader();
            CSVParser parser = new CSVParser(reader, format);

            for (CSVRecord record : parser) {
                String storeId = record.get("store_id");
                if (stores.containsKey(storeId)) {
                    continue;
                }
                String name = record.isMapped("store_name") ? record.get("store_name") : null;
                String address = record.isMapped("address") ? record.get("address") : null;
                double x = Double.parseDouble(record.get("x"));
                double y = Double.parseDouble(record.get("y"));
                LocalTime startTime = LocalTime.parse(record.get("time_window_start"));
                LocalTime endTime = LocalTime.parse(record.get("time_window_end"));

                Store store = new Store(storeId, name, address, x, y, startTime, endTime);
                stores.put(storeId, store);
            }
        }

        return new ArrayList<>(stores.values());
    }

    public static List<Truck> loadTrucks(String filename) throws IOException {
        List<Truck> trucks = new ArrayList<>();
        
        try (Reader reader = new FileReader(filename)) {
            CSVFormat format = CSVFormat.DEFAULT.withFirstRecordAsHeader();
            CSVParser parser = new CSVParser(reader, format);

            for (CSVRecord record : parser) {
                String truckId = record.get("truck_id");
                String truckName = record.isMapped("truck_name") ? record.get("truck_name") : null;
                String vehicleType = record.isMapped("vehicle_type") ? record.get("vehicle_type") : null;
                String driverName = record.isMapped("driver_name") ? record.get("driver_name") : null;
                double capacity = Double.parseDouble(record.get("capacity"));
                double costPerKm = Double.parseDouble(record.get("cost_per_km"));
                double startX = Double.parseDouble(record.get("start_x"));
                double startY = Double.parseDouble(record.get("start_y"));

                trucks.add(new Truck(truckId, truckName, vehicleType, driverName, capacity, costPerKm, startX, startY));
            }
        }

        return trucks;
    }

    public static Map<String, List<DeliveryRequest>> loadDemands(String filename, Map<String, Product> products) 
            throws IOException {
        Map<String, List<DeliveryRequest>> demandsByStore = new HashMap<>();
        
        try (Reader reader = new FileReader(filename)) {
            CSVFormat format = CSVFormat.DEFAULT.withFirstRecordAsHeader();
            CSVParser parser = new CSVParser(reader, format);

            int requestCounter = 0;
            for (CSVRecord record : parser) {
                String storeId = record.get("store_id");
                String productId = record.get("product_id");
                int quantity = Integer.parseInt(record.get("demand"));

                Product product = products.get(productId);
                if (product == null) {
                    System.err.println("Товар не найден: " + productId);
                    continue;
                }

                double totalWeight = quantity * product.getUnitWeight();
                DeliveryRequest request = new DeliveryRequest(
                    "REQ_" + (++requestCounter),
                    storeId,
                    productId,
                    quantity,
                    totalWeight
                );

                demandsByStore.computeIfAbsent(storeId, k -> new ArrayList<>()).add(request);
            }
        }

        return demandsByStore;
    }
}
