-- One-time local setup. Run as root.
CREATE DATABASE IF NOT EXISTS food_delivery      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS food_delivery_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Dedicated app user (least privilege: only these two schemas, never root).
CREATE USER IF NOT EXISTS 'food_app'@'localhost' IDENTIFIED BY 'food_app_pwd';
GRANT ALL PRIVILEGES ON food_delivery.*      TO 'food_app'@'localhost';
GRANT ALL PRIVILEGES ON food_delivery_test.* TO 'food_app'@'localhost';
FLUSH PRIVILEGES;
