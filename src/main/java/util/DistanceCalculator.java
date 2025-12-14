package util;

/**
 * Утилиты для расчёта расстояний и затрат
 */
public class DistanceCalculator {

    /**
     * Вычисляет евклидово расстояние между двумя точками
     */
    public static double calculateDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Вычисляет стоимость доставки на расстояние
     */
    public static double calculateCost(double distance, double costPerKm) {
        return distance * costPerKm;
    }

    /**
     * Расчитывает суммарное расстояние для последовательности точек
     */
    public static double calculateTotalDistance(double[] xs, double[] ys) {
        if (xs.length < 2) return 0;
        double total = 0;
        for (int i = 0; i < xs.length - 1; i++) {
            total += calculateDistance(xs[i], ys[i], xs[i + 1], ys[i + 1]);
        }
        return total;
    }

    /**
     * Вычисляет время в пути (предполагая среднюю скорость)
     */
    public static int calculateTravelTime(double distance) {
        // Средняя скорость 50 км/ч = 833 м/мин
        return (int) Math.ceil(distance / 50 * 60);  // в секундах
    }

    /**
     * Вычисляет время обслуживания в магазине (разгрузка)
     * Базовое время 5 минут + 1 минута на каждую единицу товара
     * @param totalItems общее количество единиц товара для разгрузки
     * @return время разгрузки в секундах
     */
    public static int calculateServiceTime(int totalItems) {
        // Базовое время 5 минут + 1 минута на каждую единицу товара
        int baseTimeMinutes = 5;
        int timePerItemMinutes = 1;
        return (baseTimeMinutes + totalItems * timePerItemMinutes) * 60;  // в секундах
    }
    
    /**
     * Вычисляет время обслуживания в магазине (разгрузка - базовое время 5 минут)
     * Для обратной совместимости
     */
    public static int calculateServiceTime() {
        return 5 * 60;  // 5 минут в секундах
    }
    
    /**
     * Вычисляет время погрузки на базе (10 минут)
     */
    public static int calculateLoadingTime() {
        return 10 * 60;  // 10 минут в секундах
    }
    
    /**
     * Вычисляет стоимость доставки с учетом обратного пути
     * @param distanceToStore расстояние до магазина
     * @param distanceFromStore расстояние от магазина до следующей точки (или базы)
     * @param costPerKm стоимость за километр
     * @return общая стоимость (путь туда + обратный путь * 0.7)
     */
    public static double calculateCostWithReturn(double distanceToStore, double distanceFromStore, double costPerKm) {
        return distanceToStore * costPerKm + distanceFromStore * costPerKm * 0.7;
    }
}
