package io;

import model.DeliveryRoute;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.List;

/**
 * Запись результатов доставки в Excel файл
 */
public class ScheduleWriter {

    public static void writeScheduleToExcel(String filename, List<DeliveryRoute> routes) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Delivery Schedule");

        // Стили
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        CellStyle borderStyle = workbook.createCellStyle();
        borderStyle.setBorderBottom(BorderStyle.THIN);
        borderStyle.setBorderTop(BorderStyle.THIN);
        borderStyle.setBorderLeft(BorderStyle.THIN);
        borderStyle.setBorderRight(BorderStyle.THIN);

        // Заголовки
        String[] headers = {
            "Truck ID", "Store ID", "Product ID", "Quantity", 
            "Distance (km)", "Arrival Time", "Departure Time", 
            "Route Distance (km)", "Total Cost"
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Данные маршрутов
        int rowNum = 1;
        for (DeliveryRoute route : routes) {
            for (DeliveryRoute.RouteStop stop : route.getStops()) {
                for (DeliveryRoute.DeliveryItem item : stop.getItems()) {
                    Row row = sheet.createRow(rowNum++);

                    row.createCell(0).setCellValue(route.getTruckId());
                    row.createCell(1).setCellValue(stop.getStoreId());
                    row.createCell(2).setCellValue(item.getProductId());
                    row.createCell(3).setCellValue(item.getQuantity());
                    row.createCell(4).setCellValue(String.format("%.2f", stop.getDistanceFromPreviousStop()));
                    row.createCell(5).setCellValue(stop.getArrivalTime() != null ? stop.getArrivalTime().toString() : "-");
                    row.createCell(6).setCellValue(stop.getDepartureTime() != null ? stop.getDepartureTime().toString() : "-");
                    row.createCell(7).setCellValue(String.format("%.2f", route.getTotalDistance()));
                    row.createCell(8).setCellValue(String.format("%.2f", route.getTotalCost()));

                    for (int i = 0; i < headers.length; i++) {
                        row.getCell(i).setCellStyle(borderStyle);
                    }
                }
            }
        }

        // Ширина колонок
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Добавляем сводку
        int summaryRow = rowNum + 2;
        Row summaryHeader = sheet.createRow(summaryRow);
        summaryHeader.createCell(0).setCellValue("TOTAL SUMMARY");
        summaryHeader.getCell(0).setCellStyle(headerStyle);

        double totalDistance = 0;
        double totalCost = 0;
        int totalDeliveries = 0;

        for (DeliveryRoute route : routes) {
            totalDistance += route.getTotalDistance();
            totalCost += route.getTotalCost();
            for (DeliveryRoute.RouteStop stop : route.getStops()) {
                totalDeliveries += stop.getItems().size();
            }
        }

        Row summary1 = sheet.createRow(summaryRow + 1);
        summary1.createCell(0).setCellValue("Total Distance (km):");
        summary1.createCell(1).setCellValue(String.format("%.2f", totalDistance));

        Row summary2 = sheet.createRow(summaryRow + 2);
        summary2.createCell(0).setCellValue("Total Cost:");
        summary2.createCell(1).setCellValue(String.format("%.2f", totalCost));

        Row summary3 = sheet.createRow(summaryRow + 3);
        summary3.createCell(0).setCellValue("Total Deliveries:");
        summary3.createCell(1).setCellValue(totalDeliveries);

        try (FileOutputStream fos = new FileOutputStream(filename)) {
            workbook.write(fos);
        }

        workbook.close();
        System.out.println("Расписание доставки сохранено в: " + filename);
    }

    public static void writeScheduleToCSV(String filename, List<DeliveryRoute> routes) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            // Заголовок
            writer.println("truck_id,store_id,product_id,quantity,distance_km,arrival_time,departure_time,route_distance_km,total_cost");

            // Данные
            for (DeliveryRoute route : routes) {
                for (DeliveryRoute.RouteStop stop : route.getStops()) {
                    for (DeliveryRoute.DeliveryItem item : stop.getItems()) {
                        writer.printf("%s,%s,%s,%d,%.2f,%s,%s,%.2f,%.2f%n",
                            route.getTruckId(),
                            stop.getStoreId(),
                            item.getProductId(),
                            item.getQuantity(),
                            stop.getDistanceFromPreviousStop(),
                            stop.getArrivalTime(),
                            stop.getDepartureTime(),
                            route.getTotalDistance(),
                            route.getTotalCost()
                        );
                    }
                }
            }

            // Сводка
            writer.println();
            writer.println("# SUMMARY");

            double totalDistance = 0;
            double totalCost = 0;
            int totalDeliveries = 0;

            for (DeliveryRoute route : routes) {
                totalDistance += route.getTotalDistance();
                totalCost += route.getTotalCost();
                for (DeliveryRoute.RouteStop stop : route.getStops()) {
                    totalDeliveries += stop.getItems().size();
                }
            }

            writer.printf("# Total Distance: %.2f km%n", totalDistance);
            writer.printf("# Total Cost: %.2f%n", totalCost);
            writer.printf("# Total Deliveries: %d%n", totalDeliveries);
        }

        System.out.println("Расписание доставки сохранено в: " + filename);
    }
}
