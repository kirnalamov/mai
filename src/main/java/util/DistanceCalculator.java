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
     * Вычисляет время обслуживания в магазине (минимум 5 минут)
     */
    public static int calculateServiceTime() {
        return 5 * 60;  // 5 минут в секундах
    }
}
