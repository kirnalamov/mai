# Установка Maven на Windows

## Способ 1: Ручная установка (рекомендуется)

1. **Скачайте Maven:**
   - Перейдите на https://maven.apache.org/download.cgi
   - Скачайте файл `apache-maven-3.9.x-bin.zip` (последняя версия)

2. **Распакуйте архив:**
   - Распакуйте в `C:\Program Files\Apache\maven` (или другую папку)

3. **Добавьте Maven в PATH:**
   - Нажмите `Win + R`, введите `sysdm.cpl` и нажмите Enter
   - Перейдите на вкладку "Дополнительно"
   - Нажмите "Переменные среды"
   - В разделе "Системные переменные" найдите `Path` и нажмите "Изменить"
   - Нажмите "Создать" и добавьте: `C:\Program Files\Apache\maven\bin`
   - Нажмите "ОК" во всех окнах

4. **Перезапустите PowerShell** и проверьте:
   ```powershell
   mvn -version
   ```

## Способ 2: Использование Scoop (если установлен)

```powershell
scoop install maven
```

## Способ 3: Использование Chocolatey (если установлен)

```powershell
choco install maven
```

## После установки

Выполните в PowerShell:
```powershell
mvn clean package
```

---

## ⚠️ ВАЖНО: Требуется Java 11+

Ваша текущая версия Java: 1.8

Проект требует Java 11 или выше. Установите Java 11+ с:
- https://adoptium.net/ (рекомендуется)
- https://www.oracle.com/java/technologies/downloads/

После установки Java 11+ проверьте:
```powershell
java -version
```

Должно быть: `java version "11.x.x"` или выше.

