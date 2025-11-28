# PROJECT STRUCTURE & FILES GUIDE

## Полная структура файлов Java проекта

```
jade-delivery-system/
│
├── pom.xml
│   └─ Maven конфигурация с зависимостями (JADE, Apache Commons CSV, Apache POI)
│
├── README.md
│   └─ Подробная документация проекта
│
├── jade.properties
│   └─ Конфигурация JADE платформы
│
├── src/main/java/com/multiagent/
│
│   ├── agents/
│   │   ├── WarehouseAgent.java
│   │   │   └─ Агент склада
│   │   │      Функции: управление инвентарём, бронирование товаров
│   │   │      Поведение: обработка REQUEST сообщений
│   │   │
│   │   ├── CoordinatorAgent.java
│   │   │   └─ Координирующий агент
│   │   │      Функции: загрузка данных, планирование маршрутов, запись расписания
│   │   │      Поведение: инициализация → планирование → отправка маршрутов
│   │   │
│   │   ├── StoreAgent.java
│   │   │   └─ Агент магазина (множество)
│   │   │      Функции: приём заказов, уведомления о доставке
│   │   │      Поведение: обработка REQUEST и INFORM сообщений
│   │   │
│   │   └── TruckAgent.java
│   │       └─ Агент грузовика (множество)
│   │          Функции: выполнение маршрутов, отчёты координатору
│   │          Поведение: назначение маршрута → выполнение → отчёт
│   │
│   ├── model/
│   │   ├── Product.java
│   │   │   └─ Товар (productId, name, unitWeight, currentStock)
│   │   │
│   │   ├── Store.java
│   │   │   └─ Магазин (storeId, x, y, timeWindowStart/End)
│   │   │      Метод: isWithinTimeWindow(LocalTime)
│   │   │
│   │   ├── Truck.java
│   │   │   └─ Грузовик (truckId, capacity, costPerKm, currentLoad)
│   │   │      Методы: hasCapacity(), addLoad(), removeLoad()
│   │   │
│   │   ├── DeliveryRequest.java
│   │   │   └─ Запрос на доставку (requestId, storeId, productId, quantity, status)
│   │   │      Статусы: PENDING → ACCEPTED → DELIVERED или FAILED
│   │   │
│   │   └── DeliveryRoute.java
│   │       └─ Маршрут доставки
│   │           ├─ RouteStop (вложенный класс)
│   │           │   └─ Остановка на маршруте с товарами и временем
│   │           └─ DeliveryItem (вложенный класс)
│   │               └─ Товар в грузовике
│   │
│   ├── io/
│   │   ├── DataLoader.java
│   │   │   └─ Чтение CSV файлов
│   │   │      Методы:
│   │   │      - loadProducts(filename) → List<Product>
│   │   │      - loadStores(filename) → List<Store>
│   │   │      - loadTrucks(filename) → List<Truck>
│   │   │      - loadDemands(filename, products) → Map<String, List<DeliveryRequest>>
│   │   │
│   │   └── ScheduleWriter.java
│   │       └─ Запись результатов в файлы
│   │          Методы:
│   │          - writeScheduleToCSV(filename, routes)
│   │          - writeScheduleToExcel(filename, routes)
│   │
│   ├── planning/
│   │   └── RoutePlanningEngine.java
│   │       └─ Алгоритм планирования маршрутов
│   │          Методы:
│   │          - planRoutes(...) → List<DeliveryRoute>
│   │          - buildRoute(...) → DeliveryRoute
│   │          - findNearestStore(...) → String (storeId)
│   │
│   ├── util/
│   │   └── DistanceCalculator.java
│   │       └─ Вспомогательные функции
│   │          Методы:
│   │          - calculateDistance(x1, y1, x2, y2) → double
│   │          - calculateCost(distance, costPerKm) → double
│   │          - calculateTravelTime(distance) → int
│   │          - calculateServiceTime() → int
│   │
│   ├── server/
│   │   └── ServerLauncher.java
│   │       └─ Точка входа для сервера
│   │          Задачи:
│   │          1. Создание Profile (главный контейнер)
│   │          2. Создание Runtime и AgentContainer
│   │          3. Загрузка данных из CSV
│   │          4. Создание и запуск агентов
│   │
│   └── client/
│       └── ClientLauncher.java
│           └─ Точка входа для клиента
│              Задачи:
│              1. Подключение к удалённому контейнеру
│              2. Создание AgentContainer с параметрами сервера
│              3. Создание и запуск агентов на клиенте
│
├── data/
│   ├── products.csv
│   │   └─ Каталог товаров
│   │      Формат: product_id, name, unit_weight
│   │      Пример: PROD_001, Молоко, 0.5
│   │
│   ├── stores.csv
│   │   └─ Магазины и их потребности
│   │      Формат: store_id, x, y, time_window_start, time_window_end, product_id, demand
│   │      Пример: STORE_001, 10.0, 20.0, 09:00, 12:00, PROD_001, 10
│   │
│   └── trucks.csv
│       └─ Парк грузовиков
│          Формат: truck_id, capacity, cost_per_km, start_x, start_y
│          Пример: TRUCK_001, 50.0, 10.5, 0.0, 0.0
│
└── output/
    ├── schedule.csv
    │   └─ Результаты в CSV (текстовый формат)
    │      Генерируется автоматически при запуске coordinator
    │
    └── schedule.xlsx
        └─ Результаты в Excel (форматированный файл)
           Генерируется автоматически при запуске coordinator
```

## Описание ключевых методов

### RoutePlanningEngine.planRoutes()

**Вход:**
- List<Truck> trucks
- Map<String, Store> storesMap
- Map<String, Product> productsMap
- Map<String, List<DeliveryRequest>> demands

**Выход:**
- List<DeliveryRoute> routes

**Алгоритм:**

```
для каждого грузовика:
  маршрут = пустой список остановок
  текущая_позиция = склад
  текущее_время = 09:00
  текущая_загрузка = 0
  
  пока есть неудовлетворённые потребности:
    найди ближайший магазин с потребностями
    если магазин недостижим (нет места):
      перейти к следующему грузовику
    
    рассчитай расстояние до магазина
    рассчитай время в пути
    добавь товары в остановку (если вместятся)
    добавь остановку в маршрут
    обновляй текущую позицию и время
  
  добавь маршрут в результаты
  
вернуть все маршруты
```

### DataLoader.loadStores()

**Процесс:**
1. Открыть CSV файл с заголовками
2. Для каждой строки:
   - Прочитать store_id, x, y, time_window_start, time_window_end
   - Создать объект Store
   - Добавить в список
3. Вернуть список Store

**Исключения:**
- FileNotFoundException: файл не найден
- IOException: ошибка чтения
- NumberFormatException: неправильный формат координат

## Запуск проекта - Пошаговая инструкция

### Шаг 1: Сборка

```bash
cd jade-delivery-system
mvn clean package
```

Результат: `target/jade-delivery-system.jar` (~80 MB)

### Шаг 2: Запуск сервера

```bash
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
```

Ожидание: 3-5 секунд на инициализацию

### Шаг 3: Запуск клиента (в новом терминале)

```bash
java -cp target/jade-delivery-system.jar client.ClientLauncher localhost 1099 Client2
```

### Шаг 4: Проверка результатов

```bash
# CSV
cat output/schedule.csv

# Excel (откройте в программе)
# libreoffice output/schedule.xlsx  (Linux)
# start output/schedule.xlsx         (Windows)
```

## Взаимодействие между агентами

### 1. Инициализация

```
ServerLauncher
  ↓
Runtime.instance() → Profile
  ↓
mainContainer.createNewAgent("coordinator", CoordinatorAgent, null)
  ↓
CoordinatorAgent.setup()
  ↓ loadProducts(), loadStores(), loadTrucks(), loadDemands()
  ↓
mainContainer.createNewAgent("warehouse", WarehouseAgent, null)
mainContainer.createNewAgent("store_STORE_001", StoreAgent, [store])
mainContainer.createNewAgent("TRUCK_001", TruckAgent, [truck])
```

### 2. Обмен сообщениями

```
CoordinatorAgent
  (1) Загружает данные
  (2) Вызывает RoutePlanningEngine.planRoutes()
  (3) Вызывает ScheduleWriter.writeScheduleToCSV/Excel()
  (4) Отправляет INFORM сообщения TruckAgent:
      "ROUTE: ROUTE_1"

TruckAgent
  (5) Получает сообщение
  (6) Отправляет AGREE:
      "ROUTE_ACCEPTED: ROUTE_1"
  (7) После выполнения отправляет INFORM:
      "ROUTE_COMPLETED: ROUTE_1: status=SUCCESS"
```

### 3. Поиск по DF (Directory Facilitator)

```
StoreAgent
  Регистрирует себя:
  ├─ name: "store"
  ├─ type: "service"

CoordinatorAgent
  Может найти StoreAgent:
  DFService.search(this, dfd_template)
  → возвращает список AID agentov
```

## Параметры, которые можно настраивать

В файле `CoordinatorAgent.java`:

```java
LocalTime departureTime = LocalTime.of(9, 0);  // Время выезда
```

В файле `DistanceCalculator.java`:

```java
double speed = 50;  // км/ч (для расчёта времени)
int serviceTime = 5 * 60;  // 5 минут на магазин
```

В файлах данных (`data/`):

```csv
# Координаты магазинов (x, y)
# Временные окна (time_window_start, time_window_end)
# Грузоподъёмность (capacity)
# Стоимость доставки (cost_per_km)
```

## Тестирование локально (полный цикл)

```bash
# Терминал 1: Сервер
java -cp target/jade-delivery-system.jar server.ServerLauncher

# Терминал 2: Клиент (ждите 2-3 секунды после запуска сервера)
sleep 3
java -cp target/jade-delivery-system.jar client.ClientLauncher localhost

# Терминал 3: Проверка результатов (ждите 5 секунд)
sleep 5
cat output/schedule.csv
head -20 output/schedule.csv | column -t -s,

# Чтобы остановить: Ctrl+C в терминалах 1 и 2
```

## Отладка

Для включения debug режима отредактируйте строки в классах:

```java
// Добавить перед System.out.println:
logger.debug("Debug message: " + variable);

// Или просто:
System.out.println("[DEBUG] " + message);
```

## Масштабирование данных

Для тестирования с большим количеством данных отредактируйте CSV файлы:

```bash
# Генерировать 100 магазинов
python3 << 'EOF'
with open('data/stores.csv', 'w') as f:
    f.write('store_id,x,y,time_window_start,time_window_end,product_id,demand\n')
    for i in range(100):
        f.write(f'STORE_{i},{ i * 10}.0,{ (i * 5) % 500}.0,09:00,18:00,PROD_001,{(i % 10) + 1}\n')
EOF
```

---

**Важно:** Все файлы должны быть в кодировке UTF-8!
