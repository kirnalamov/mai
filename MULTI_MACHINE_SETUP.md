# ЗАПУСК НА ДВУХ МАШИНАХ - Пошаговая инструкция

## Сценарий: Машина 1 (Сервер) и Машина 2 (Клиент)

### Требования

- Java 11+ установлена на обеих машинах
- Обе машины в одной сети (или есть доступ по IP)
- Maven (опционально, если собирать заново)

### Этап 1: Подготовка на Машине 1 (Сервер)

#### 1.1 Скопировать проект

```bash
# На сервере (Машина 1)
cd /путь/к/проекту
ls
# Должны быть: pom.xml, data/, src/, target/ (после сборки)
```

#### 1.2 Собрать проект (если не собран)

```bash
mvn clean package
# Результат: target/jade-delivery-system.jar (~80 MB)
```

#### 1.3 Проверить доступные порты

```bash
# Linux/Mac
netstat -an | grep 1099

# Windows
netstat -ano | findstr "1099"
```

Если портует свободен, можно запускать сервер.

#### 1.4 Получить IP адрес сервера

```bash
# Linux/Mac
ifconfig | grep "inet "

# Windows
ipconfig

# Искать что-то вроде: 192.168.1.100 или 10.0.0.50
# (не 127.0.0.1, это локальный адрес)
```

**Запомните IP адрес! (например: 192.168.1.100)**

---

### Этап 2: Запуск сервера на Машине 1

```bash
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
```

**Ожидаемый результат:**

```
=== Запуск серверной части JADE ===
Порт: 1099
Главный контейнер создан
Загрузка данных...
✓ Данные загружены успешно
  - Товаров: 10
  - Магазинов: 6
  - Грузовиков: 4

Создание агентов...
✓ CoordinatorAgent запущен
✓ WarehouseAgent запущен
✓ StoreAgent запущен для магазина: STORE_001
✓ StoreAgent запущен для магазина: STORE_002
✓ TruckAgent запущен: TRUCK_001
✓ TruckAgent запущен: TRUCK_002
✓ TruckAgent запущен: TRUCK_003
✓ TruckAgent запущен: TRUCK_004

=== Сервер готов к работе ===
Для подключения клиентов используйте адрес: localhost:1099
Нажмите Ctrl+C для завершения
```

**⚠️ Важно:** Не закрывайте это окно терминала! Оставьте сервер работать.

---

### Этап 3: Подготовка на Машине 2 (Клиент)

#### 3.1 Скопировать проект

```bash
# На клиенте (Машина 2)
# Скопируйте весь проект, либо только скомпилированный JAR:

scp -r user@192.168.1.100:/путь/к/jade-delivery-system /локальный/путь/
# или
scp user@192.168.1.100:/путь/к/target/jade-delivery-system.jar ./
```

#### 3.2 Подготовить данные (опционально)

Если клиент запускает StoreAgent, убедитесь, что файлы data/ скопированы:

```bash
ls data/
# Должны быть: products.csv, stores.csv, trucks.csv
```

---

### Этап 4: Запуск клиента на Машине 2

**Команда:**

```bash
java -cp jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 ClientMachine2
```

Где:
- `192.168.1.100` — IP адрес сервера (Машина 1)
- `1099` — порт сервера
- `ClientMachine2` — имя контейнера на клиенте

**Ожидаемый результат:**

```
=== Запуск клиентской части JADE ===
Подключение к серверу: 192.168.1.100:1099
Имя контейнера: ClientMachine2
Загрузка данных...
✓ Данные загружены

Создание агентов на клиенте...
✓ StoreAgent запущен: store_STORE_003
✓ StoreAgent запущен: store_STORE_004
✓ TruckAgent запущен: TRUCK_001_remote

=== Клиент готов к работе ===
Подключено к серверу: 192.168.1.100:1099
Нажмите Ctrl+C для завершения
```

---

### Этап 5: Проверка результатов

После запуска обоих контейнеров система начнёт планирование маршрутов.

#### На сервере (Машина 1), должны появиться логи:

```
[coordinator] Начинается планирование маршрутов...
[coordinator] Маршруты спланированы: 4
  - Route{routeId='ROUTE_1', truck='TRUCK_001', stops=2, distance=45.23, cost=475.92}
  - Route{routeId='ROUTE_2', truck='TRUCK_002', stops=3, distance=62.15, cost=745.80}
  ...
```

#### Проверить файлы результатов (на сервере):

```bash
# CSV версия
cat output/schedule.csv
# Должно выглядеть так:
# truck_id,store_id,product_id,quantity,distance_km,arrival_time,...

# Excel версия
# Откройте файл в Excel / LibreOffice Calc:
# output/schedule.xlsx
```

---

## Альтернативный сценарий: Несколько клиентов

Вы можете запустить несколько клиентов одновременно:

### Машина 1 (Сервер)

```bash
java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
```

### Машина 2 (Клиент 1)

```bash
java -cp jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 Client2
```

### Машина 3 (Клиент 2)

```bash
java -cp jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 Client3
```

### Машина 4 (Клиент 3)

```bash
java -cp jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 Client4
```

Система будет распределять агентов магазинов по разным клиентам.

---

## Troubleshooting: Частые ошибки

### Ошибка 1: "Connection refused"

**Симптом:**
```
Exception in thread "main" java.net.ConnectException: Connection refused
```

**Причины и решения:**

1. **Сервер не запущен**
   ```bash
   # На машине 1, убедитесь, что сервер запущен
   ps aux | grep ServerLauncher
   ```

2. **Неверный IP адрес**
   ```bash
   # На машине 2, проверьте IP машины 1
   ping 192.168.1.100  # Замените на реальный IP
   ```

3. **Портец заблокирован брандмауэром**
   ```bash
   # На машине 1 (Linux)
   sudo ufw allow 1099/tcp
   
   # На машине 1 (Windows)
   # Откройте Windows Firewall и разрешите порт 1099
   ```

### Ошибка 2: "Cannot find class server.ServerLauncher"

**Решение:**
```bash
# Убедитесь, что JAR скомпилирован
mvn clean package

# Проверьте, что вы в корневой папке проекта
ls target/jade-delivery-system.jar
```

### Ошибка 3: "File not found: data/products.csv"

**Решение:**
```bash
# На каждой машине (клиент тоже должен иметь доступ к файлам)
mkdir -p data
# Скопируйте CSV файлы:
scp user@server:/путь/к/data/* ./data/
```

### Ошибка 4: "Address already in use: 1099"

**Решение:**
```bash
# Используйте другой порт на клиенте
java -cp jade-delivery-system.jar client.ClientLauncher 192.168.1.100 1099 Client2

# Или завершите старый процесс (Linux)
lsof -i :1099
kill -9 <PID>
```

### Ошибка 5: "java: command not found"

**Решение:**
```bash
# Убедитесь, что Java установлена
java -version

# Установите Java (Ubuntu)
sudo apt-get install openjdk-11-jdk

# Установите Java (macOS)
brew install openjdk@11

# Установите Java (Windows)
# Скачайте с https://www.oracle.com/java/technologies/downloads/
```

---

## Команды для быстрой диагностики

```bash
# Проверить, работает ли сервер
curl http://192.168.1.100:1099

# Проверить открытые порты на сервере
netstat -an | grep LISTEN

# Проверить логи (если есть)
tail -100 /var/log/syslog  # Linux

# Проверить доступность сервера с клиента
ping 192.168.1.100
traceroute 192.168.1.100   # или tracert на Windows

# Проверить на каком IP адресе сервер
ifconfig         # Linux/Mac
ipconfig         # Windows
```

---

## Быстрый чек-лист запуска

### На Машине 1 (Сервер):

- [ ] Java 11+ установлена (`java -version`)
- [ ] Проект скомпилирован (`mvn clean package`)
- [ ] Файлы `data/*.csv` присутствуют
- [ ] Порт 1099 свободен (`netstat -an | grep 1099`)
- [ ] Известен IP адрес сервера (`ifconfig`)
- [ ] Сервер запущен:
  ```bash
  java -cp target/jade-delivery-system.jar server.ServerLauncher 1099
  ```

### На Машине 2 (Клиент):

- [ ] Java 11+ установлена (`java -version`)
- [ ] JAR скопирован или скомпилирован
- [ ] Файлы `data/*.csv` присутствуют
- [ ] IP адрес сервера правильный
- [ ] Порт сервера открыт (`ping server_ip`)
- [ ] Клиент запущен:
  ```bash
  java -cp jade-delivery-system.jar client.ClientLauncher <server_ip> 1099 Client2
  ```

### На Машине 1, проверить результаты:

- [ ] В консоли логи о планировании маршрутов
- [ ] Файл `output/schedule.csv` создан и заполнен
- [ ] Файл `output/schedule.xlsx` создан и можно открыть

---

## Более сложный сценарий: Docker

Если хотите запускать в контейнерах:

```dockerfile
FROM openjdk:11-jre-slim

WORKDIR /app
COPY target/jade-delivery-system.jar .
COPY data/ data/
COPY jade.properties .

ENTRYPOINT ["java", "-cp", "jade-delivery-system.jar"]
```

```bash
# Собрать образ
docker build -t jade-delivery .

# Запустить сервер
docker run -p 1099:1099 -v $(pwd)/output:/app/output jade-delivery \
  server.ServerLauncher 1099

# Запустить клиент (из другого терминала)
docker run --network host jade-delivery \
  client.ClientLauncher localhost 1099 DockerClient2
```

---

**Готово! Система должна работать на двух машинах.**
