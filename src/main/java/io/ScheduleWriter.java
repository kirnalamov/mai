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
        Sheet sheet = workbook.createSheet("Расписание");

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

        DataFormat dataFormat = workbook.createDataFormat();
        CellStyle numberStyle = workbook.createCellStyle();
        numberStyle.cloneStyleFrom(borderStyle);
        numberStyle.setDataFormat(dataFormat.getFormat("#,##0.00"));

        CellStyle currencyStyle = workbook.createCellStyle();
        currencyStyle.cloneStyleFrom(borderStyle);
        currencyStyle.setDataFormat(dataFormat.getFormat("#,##0.00 \"₽\""));

        CellStyle integerStyle = workbook.createCellStyle();
        integerStyle.cloneStyleFrom(borderStyle);
        integerStyle.setDataFormat(dataFormat.getFormat("0"));

        // Заголовки
        String[] headers = {
            "Маршрут",
            "Автомобиль",
            "Тип ТС",
            "Водитель",
            "Грузоподъёмность, т",
            "Магазин",
            "Адрес",
            "Окно приёмки",
            "Товар",
            "Количество, шт",
            "Вес партии, т",
            "Дистанция до точки, км",
            "Прибытие",
            "Отправление",
            "Пробег маршрута, км",
            "Стоимость маршрута, ₽"
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

                    int col = 0;
                    row.createCell(col++).setCellValue(route.getRouteId());
                    row.createCell(col++).setCellValue(route.getTruckName());
                    row.createCell(col++).setCellValue(route.getVehicleType());
                    row.createCell(col++).setCellValue(route.getDriverName());
                    Cell capacityCell = row.createCell(col++);
                    capacityCell.setCellValue(route.getTruckCapacity());
                    capacityCell.setCellStyle(numberStyle);

                    row.createCell(col++).setCellValue(stop.getStoreName());
                    row.createCell(col++).setCellValue(stop.getAddress());
                    row.createCell(col++).setCellValue(stop.getTimeWindow());
                    row.createCell(col++).setCellValue(item.getProductName());

                    Cell quantityCell = row.createCell(col++);
                    quantityCell.setCellValue(item.getQuantity());
                    quantityCell.setCellStyle(integerStyle);

                    Cell weightCell = row.createCell(col++);
                    weightCell.setCellValue(item.getWeight());
                    weightCell.setCellStyle(numberStyle);

                    Cell distanceCell = row.createCell(col++);
                    distanceCell.setCellValue(stop.getDistanceFromPreviousStop());
                    distanceCell.setCellStyle(numberStyle);

                    row.createCell(col++).setCellValue(stop.getArrivalTime() != null ? stop.getArrivalTime().toString() : "-");
                    row.createCell(col++).setCellValue(stop.getDepartureTime() != null ? stop.getDepartureTime().toString() : "-");

                    Cell routeDistanceCell = row.createCell(col++);
                    routeDistanceCell.setCellValue(route.getTotalDistance());
                    routeDistanceCell.setCellStyle(numberStyle);

                    Cell routeCostCell = row.createCell(col++);
                    routeCostCell.setCellValue(route.getTotalCost());
                    routeCostCell.setCellStyle(currencyStyle);

                    for (int i = 0; i < headers.length; i++) {
                        Cell c = row.getCell(i);
                        if (c != null && c.getCellStyle() == null) {
                            c.setCellStyle(borderStyle);
                        }
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
        summaryHeader.createCell(0).setCellValue("ИТОГИ");
        summaryHeader.getCell(0).setCellStyle(headerStyle);

        double totalDistance = 0;
        double totalCost = 0;
        int totalDeliveries = 0;
        double totalWeight = 0;
        java.util.Set<String> servedStores = new java.util.HashSet<>();

        for (DeliveryRoute route : routes) {
            totalDistance += route.getTotalDistance();
            totalCost += route.getTotalCost();
            for (DeliveryRoute.RouteStop stop : route.getStops()) {
                totalDeliveries += stop.getItems().size();
                servedStores.add(stop.getStoreId());
                for (DeliveryRoute.DeliveryItem item : stop.getItems()) {
                    totalWeight += item.getWeight();
                }
            }
        }

        Row summary1 = sheet.createRow(summaryRow + 1);
        summary1.createCell(0).setCellValue("Суммарный пробег, км:");
        Cell totalDistanceCell = summary1.createCell(1);
        totalDistanceCell.setCellValue(totalDistance);
        totalDistanceCell.setCellStyle(numberStyle);

        Row summary2 = sheet.createRow(summaryRow + 2);
        summary2.createCell(0).setCellValue("Совокупная стоимость, ₽:");
        Cell totalCostCell = summary2.createCell(1);
        totalCostCell.setCellValue(totalCost);
        totalCostCell.setCellStyle(currencyStyle);

        Row summary3 = sheet.createRow(summaryRow + 3);
        summary3.createCell(0).setCellValue("Доставок (позиций):");
        Cell totalDeliveriesCell = summary3.createCell(1);
        totalDeliveriesCell.setCellValue(totalDeliveries);
        totalDeliveriesCell.setCellStyle(integerStyle);

        Row summary4 = sheet.createRow(summaryRow + 4);
        summary4.createCell(0).setCellValue("Отгруженный вес, т:");
        Cell totalWeightCell = summary4.createCell(1);
        totalWeightCell.setCellValue(totalWeight);
        totalWeightCell.setCellStyle(numberStyle);

        Row summary5 = sheet.createRow(summaryRow + 5);
        summary5.createCell(0).setCellValue("Обслужено магазинов:");
        Cell servedStoresCell = summary5.createCell(1);
        servedStoresCell.setCellValue(servedStores.size());
        servedStoresCell.setCellStyle(integerStyle);

        try (FileOutputStream fos = new FileOutputStream(filename)) {
            workbook.write(fos);
        }

        workbook.close();
        System.out.println("Расписание доставки сохранено в: " + filename);
    }

    public static void writeScheduleToCSV(String filename, List<DeliveryRoute> routes) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("route_id,truck_name,vehicle_type,driver_name,truck_capacity_t,store_name,address,time_window,product_name,quantity,weight_t,distance_to_store_km,arrival_time,departure_time,route_distance_km,route_cost_rub");

            // Данные
            for (DeliveryRoute route : routes) {
                for (DeliveryRoute.RouteStop stop : route.getStops()) {
                    for (DeliveryRoute.DeliveryItem item : stop.getItems()) {
                        writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",%.2f,\"%s\",\"%s\",\"%s\",\"%s\",%d,%.2f,%.2f,%s,%s,%.2f,%.2f%n",
                            route.getRouteId(),
                            escape(route.getTruckName()),
                            escape(route.getVehicleType()),
                            escape(route.getDriverName()),
                            route.getTruckCapacity(),
                            escape(stop.getStoreName()),
                            escape(stop.getAddress()),
                            escape(stop.getTimeWindow()),
                            escape(item.getProductName()),
                            item.getQuantity(),
                            item.getWeight(),
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
            writer.println("# ИТОГИ");

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

            writer.printf("# Суммарный пробег: %.2f км%n", totalDistance);
            writer.printf("# Совокупная стоимость: %.2f ₽%n", totalCost);
            writer.printf("# Позиций доставлено: %d%n", totalDeliveries);
        }

        System.out.println("Расписание доставки сохранено в: " + filename);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\"\"");
    }
}
