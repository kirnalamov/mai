# JADE Delivery System - Мультиагентная система доставки товаров

## Описание проекта

**JADE Delivery System** — это учебный Java-проект мультиагентной системы (МАС) на базе фреймворка JADE. Система решает задачу оптимизации доставки товаров со склада в магазины с учётом следующих ограничений:

- **Временные окна приёмки** для каждого магазина (например, 09:00–12:00)
- **Грузоподъёмность автомобилей** (ограничение по весу/объёму)
- **Минимизация суммарных затрат** на доставку (расстояния и стоимость пробега)
- **Работа на нескольких машинах** (сервер + удалённые клиенты)

## Архитектура системы

### Основные компоненты

#### 1. **Агенты JADE**

- **CoordinatorAgent** (`coordinator`)
  - Главный координатор системы
  - Загружает данные из CSV файлов
  - Планирует маршруты доставки с помощью алгоритма RoutePlanningEngine
  - Генерирует расписание в формате CSV/Excel

- **WarehouseAgent** (`warehouse`)
  - Управляет запасами товаров на складе
  - Обрабатывает запросы на проверку инвентаря
  - Резервирует товары для отправки

- **StoreAgent** (один на магазин)
  - Представляет магазин
  - Подаёт запросы на доставку товаров
  - Находится локально на сервере или удалённо на клиенте

- **TruckAgent** (один на грузовик)
  - Представляет автомобиль доставки
  - Получает маршруты от координатора
  - Выполняет доставку в магазины

#### 2. **Модели данных** (папка `model/`)

- `Product` — товар с весом и ID
- `Store` — магазин с координатами и временным окном
- `Truck` — грузовик с грузоподъёмностью и стоимостью пробега
- `DeliveryRequest` — запрос на доставку товара
- `DeliveryRoute` — маршрут для одного грузовика с остановками

#### 3. **Входные данные** (папка `data/`)

- `products.csv` — каталог товаров
- `stores.csv` — магазины, их адреса, временные окна и потребности
- `trucks.csv` — грузовики, их параметры

#### 4. **Выходные данные** (папка `output/`)

- `schedule.csv` — расписание доставки (текстовый формат)
- `schedule.xlsx` — расписание доставки (Excel формат с форматированием)

#### 5. **Алгоритм планирования** (класс `RoutePlanningEngine`)

Использует **жадный алгоритм**:

1. Для каждого грузовика:
   - Выбираем ближайший магазин с непокрытыми потребностями
   - Проверяем возможность доставить товары (грузоподъёмность + временное окно)
   - Добавляем остановку в маршрут
   - Повторяем до заполнения грузовика или исчерпания магазинов

2. Рассчитываем суммарное расстояние и стоимость для каждого маршрута

3. Возвращаемся на склад после последней доставки

## Структура проекта

```
jade-delivery-system/
├── pom.xml                          # Maven конфигурация
├── README.md                        # Этот файл
├── src/
│   └── main/
│       └── java/com/multiagent/
│           ├── agents/              # JADE агенты
│           │   ├── WarehouseAgent.java
│           │   ├── CoordinatorAgent.java
│           │   ├── StoreAgent.java
│           │   └── TruckAgent.java
│           ├── model/               # Модели данных
│           │   ├── Product.java
│           │   ├── Store.java
│           │   ├── Truck.java
│           │   ├── DeliveryRequest.java
│           │   └── DeliveryRoute.java
│           ├── io/                  # Чтение/запись данных
│           │   ├── DataLoader.java
│           │   └── ScheduleWriter.java
│           ├── planning/            # Алгоритмы планирования
│           │   └── RoutePlanningEngine.java
│           ├── util/                # Утилиты
│           │   └── DistanceCalculator.java
│           ├── server/              # Запуск сервера
│           │   └── ServerLauncher.java
│           └── client/              # Запуск клиента
│               └── ClientLauncher.java
├── data/                            # Входные CSV файлы
│   ├── products.csv
│   ├── stores.csv
│   └── trucks.csv
└── output/                          # Выходные файлы (создаются автоматически)
    ├── schedule.csv
    └── schedule.xlsx
```

## Формат входных данных

### products.csv

```csv
product_id,name,unit_weight
PROD_001,Молоко,0.5
PROD_002,Хлеб,0.3
```

**Столбцы:**
- `product_id` — уникальный ID товара
- `name` — название товара
- `unit_weight` — вес одной единицы товара

### stores.csv

```csv
store_id,x,y,time_window_start,time_window_end,product_id,demand
STORE_001,10.0,20.0,09:00,12:00,PROD_001,10
STORE_001,10.0,20.0,09:00,12:00,PROD_002,5
```

**Столбцы:**
- `store_id` — уникальный ID магазина
- `x, y` — координаты магазина на плоскости
- `time_window_start, time_window_end` — временное окно приёмки (HH:MM)
- `product_id` — ID товара, который нужен магазину
- `demand` — количество единиц товара

**Примечание:** один магазин может появляться в нескольких строках (разные товары)

### trucks.csv

```csv
truck_id,capacity,cost_per_km,start_x,start_y
TRUCK_001,50.0,10.5,0.0,0.0
```

**Столбцы:**
- `truck_id` — уникальный ID грузовика
- `capacity` — грузоподъёмность (в условных единицах)
- `cost_per_km` — стоимость одного км пробега
- `start_x, start_y` — координаты склада (обычно 0,0)

## Формат выходных данных

### schedule.csv (текстовый)

```csv
truck_id,store_id,product_id,quantity,distance_km,arrival_time,departure_time,route_distance_km,total_cost
TRUCK_001,STORE_001,PROD_001,10,10.50,09:15:30,09:20:30,35.50,372.75
...
# SUMMARY
# Total Distance: 156.75 km
# Total Cost: 1846.34
# Total Deliveries: 12
```

### schedule.xlsx (Excel)

Красиво оформленная таблица с:
- Заголовками (синий фон)
- Автоширина колонок
- Сводкой внизу (общее расстояние, стоимость, количество доставок)

## Установка и сборка

### Требования

- **Java 11 или выше**
- **Maven 3.6+**
- **JADE 4.5.0** (автоматически загружается Maven)

### Шаг 1: Клонировать/подготовить проект

```bash
# Создать директорию проекта
mkdir jade-delivery-system
cd jade-delivery-system

# Скопировать все файлы (см. структуру проекта выше)
```

### Шаг 2: Создать структуру папок

```bash
mkdir -p src/main/java/com/multiagent/{agents,model,io,planning,util,server,client}
mkdir -p data
mkdir -p output
```

### Шаг 3: Поместить входные данные

Скопировать файлы `products.csv`, `stores.csv`, `trucks.csv` в папку `data/`

### Шаг 4: Собрать проект

```bash
mvn clean package
```

Результат: `target/jade-delivery-system.jar`

## Запуск системы

### Вариант 1: На одной машине (один сервер, один клиент)

**Терминал 1 — Запуск сервера:**

```bash
cd jade-delivery-system
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
```

**Ожидаемый вывод:**

```
=== Запуск серверной части JADE ===
Порт: 1099
Главный контейнер создан
...
✓ CoordinatorAgent запущен
✓ WarehouseAgent запущен
✓ StoreAgent запущен для магазина: STORE_001
...
=== Сервер готов к работе ===
Для подключения клиентов используйте адрес: localhost:1099
```

**Терминал 2 — Запуск клиента:**

```bash
cd jade-delivery-system
java -cp target/jade-delivery-system.jar client.ClientLauncher localhost 1099 Client2
```

**Ожидаемый вывод:**

```
=== Запуск клиентской части JADE ===
Подключение к серверу: localhost:1099
...
✓ StoreAgent запущен: store_STORE_002
...
=== Клиент готов к работе ===
```

### Вариант 2: На двух разных машинах

**Машина 1 (Сервер):**

```bash
# На машине с IP 192.168.1.100
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
```

**Машина 2 (Клиент):**

```bash
# На другой машине в сети
java -cp target/jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 ClientMachine2
```

## Проверка результатов

После запуска системы проверьте:

1. **Консоль сервера** — должны быть сообщения о создании агентов и планировании маршрутов
2. **Файл `output/schedule.csv`** — содержит расписание доставки
3. **Файл `output/schedule.xlsx`** — том же расписание в Excel формате

### Пример вывода

```
[coordinator] Начинается планирование маршрутов...
[coordinator] Маршруты спланированы: 2
  - Route{routeId='ROUTE_1', truck='TRUCK_001', stops=3, distance=55.25, cost=580.13}
  - Route{routeId='ROUTE_2', truck='TRUCK_002', stops=2, distance=42.10, cost=506.20}
```

## Логика работы системы

```
1. Запуск ServerLauncher
   ↓
2. Создание главного контейнера JADE
   ↓
3. Запуск CoordinatorAgent
   ↓
4. Загрузка данных из CSV файлов
   ↓
5. Запуск WarehouseAgent, StoreAgent, TruckAgent
   ↓
6. Планирование маршрутов (RoutePlanningEngine)
   ↓
7. Генерация schedule.csv и schedule.xlsx
   ↓
8. Отправка маршрутов TruckAgent
   ↓
9. Система ждёт завершения (Ctrl+C)
```

## Взаимодействие агентов

```
CoordinatorAgent
├─ (INFORM) → TruckAgent: "ROUTE: ROUTE_1"
├─ (REQUEST) ← StoreAgent: "DELIVERY_REQUEST: PROD_001, qty=10"
└─ (INFORM) ← TruckAgent: "ROUTE_COMPLETED: ROUTE_1"

WarehouseAgent
├─ (REQUEST) ← CoordinatorAgent: "INVENTORY_CHECK: PROD_001"
└─ (INFORM) → CoordinatorAgent: "INVENTORY: PROD_001: 100"

StoreAgent
├─ (REQUEST) → WarehouseAgent: "RESERVE: PROD_001: 10"
└─ (INFORM) ← CoordinatorAgent: "ROUTE: ROUTE_1"
```

## Возможные доработки

1. **Оптимизация маршрутов:**
   - Вместо жадного алгоритма использовать муравьиный алгоритм (Ant Colony Optimization)
   - Применить 2-opt или 3-opt локальный поиск

2. **Динамическая система:**
   - Добавить новые запросы на доставку во время работы
   - Пересчитывать маршруты в реальном времени

3. **Расширенная коммуникация:**
   - Полная FIPA реализация протоколов
   - Согласование цен и условий между агентами

4. **Визуализация:**
   - Веб-интерфейс для отслеживания доставок
   - Карта с маршрутами на Google Maps

5. **Масштабируемость:**
   - Балансировка нагрузки между контейнерами
   - Горизонтальное масштабирование при количестве магазинов > 100

## Примеры использования

### Запуск на локальной машине с портом 1099 (по умолчанию)

```bash
# Терминал 1
java -cp target/jade-delivery-system.jar server.ServerLauncher

# Терминал 2 (в другом окне, через 2 секунды)
java -cp target/jade-delivery-system.jar client.ClientLauncher localhost
```

### Запуск на двух машинах в сети

```bash
# На машине A (192.168.1.50):
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099

# На машине B (192.168.1.51):
java -cp target/jade-delivery-system.jar client.ClientLauncher 192.168.1.50 1099 Client2
```

## Troubleshooting

### Ошибка: `Cannot find class server.ServerLauncher`

**Решение:** Убедитесь, что проект скомпилирован:

```bash
mvn clean package
```

### Ошибка: `File not found: data/products.csv`

**Решение:** Убедитесь, что запускаете из корневой папки проекта и в папке `data/` есть CSV файлы

### Ошибка: `Address already in use: 1099`

**Решение:** Используйте другой порт:

```bash
java -cp target/jade-delivery-system.jar server.ServerLauncher 1100
```

### Клиент не подключается к серверу

**Проверьте:**

1. Сервер запущен и готов к работе
2. Правильный IP адрес (используйте `127.0.0.1` для локальной машины)
3. Порт совпадает (по умолчанию 1099)
4. Нет брандмауэра, блокирующего порт

```bash
# На машине сервера, проверьте что порт открыт:
netstat -an | grep 1099
```

## Дополнительная информация

- [JADE Documentation](https://jade.tilab.com/documentation/)
- [FIPA Agent Communication Language](http://www.fipa.org/)
- [Vehicle Routing Problem (VRP)](https://en.wikipedia.org/wiki/Vehicle_routing_problem)

## Лицензия

Учебный проект. Использование в образовательных целях.

---

**Автор:** Multiagent Systems Team  
**Дата:** 2025  
**Версия:** 1.0
