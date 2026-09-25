-- Initial schema. Conventions:
--   * BIGINT AUTO_INCREMENT ids (sequential inserts into InnoDB's clustered index)
--   * enums as VARCHAR (mapped with @Enumerated(STRING))
--   * money as DECIMAL(10,2); timestamps in UTC
--   * `active` = soft delete for rows referenced by historical orders

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    phone         VARCHAR(20),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE cities (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    state      VARCHAR(100),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cities_name UNIQUE (name)
);

CREATE TABLE restaurants (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id     BIGINT       NOT NULL,
    city_id      BIGINT       NOT NULL,
    name         VARCHAR(150) NOT NULL,
    address      VARCHAR(255) NOT NULL,
    cuisine      VARCHAR(100),
    is_open      BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    rating_sum   INT          NOT NULL DEFAULT 0,
    rating_count INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_restaurants_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_restaurants_city  FOREIGN KEY (city_id)  REFERENCES cities (id),
    INDEX idx_restaurants_city_active (city_id, active)
);

CREATE TABLE menu_items (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT        NOT NULL,
    name          VARCHAR(150)  NOT NULL,
    description   VARCHAR(500),
    category      VARCHAR(100),
    price         DECIMAL(10,2) NOT NULL,
    is_veg        BOOLEAN       NOT NULL DEFAULT TRUE,
    available     BOOLEAN       NOT NULL DEFAULT TRUE,
    stock         INT           NULL,          -- NULL = unlimited; otherwise remaining units
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_menu_items_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_menu_items_price CHECK (price >= 0),
    CONSTRAINT chk_menu_items_stock CHECK (stock IS NULL OR stock >= 0),
    INDEX idx_menu_items_restaurant_active (restaurant_id, active)
);

CREATE TABLE delivery_partners (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    city_id      BIGINT      NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    rating_sum   INT         NOT NULL DEFAULT 0,
    rating_count INT         NOT NULL DEFAULT 0,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_delivery_partners_user UNIQUE (user_id),
    CONSTRAINT fk_delivery_partners_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_delivery_partners_city FOREIGN KEY (city_id) REFERENCES cities (id),
    INDEX idx_delivery_partners_city_status (city_id, status)
);

CREATE TABLE orders (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id         BIGINT        NOT NULL,
    restaurant_id       BIGINT        NOT NULL,
    delivery_partner_id BIGINT        NULL,
    status              VARCHAR(30)   NOT NULL,
    subtotal            DECIMAL(10,2) NOT NULL,
    delivery_fee        DECIMAL(10,2) NOT NULL,
    total_amount        DECIMAL(10,2) NOT NULL,
    delivery_address    VARCHAR(500)  NOT NULL,
    idempotency_key     VARCHAR(64)   NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_customer   FOREIGN KEY (customer_id)         REFERENCES users (id),
    CONSTRAINT fk_orders_restaurant FOREIGN KEY (restaurant_id)       REFERENCES restaurants (id),
    CONSTRAINT fk_orders_partner    FOREIGN KEY (delivery_partner_id) REFERENCES delivery_partners (id),
    CONSTRAINT chk_orders_amounts CHECK (subtotal >= 0 AND delivery_fee >= 0 AND total_amount >= 0),
    -- Multiple NULL keys are allowed by MySQL, so the key stays optional.
    CONSTRAINT uk_orders_customer_idempotency UNIQUE (customer_id, idempotency_key),
    INDEX idx_orders_customer_created (customer_id, created_at),
    INDEX idx_orders_restaurant_status (restaurant_id, status),
    INDEX idx_orders_status_partner (status, delivery_partner_id)
);

CREATE TABLE order_items (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_id     BIGINT        NOT NULL,
    menu_item_id BIGINT        NOT NULL,
    item_name    VARCHAR(150)  NOT NULL,   -- snapshot at order time
    unit_price   DECIMAL(10,2) NOT NULL,   -- snapshot at order time
    quantity     INT           NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order     FOREIGN KEY (order_id)     REFERENCES orders (id),
    CONSTRAINT fk_order_items_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0)
);

CREATE TABLE order_status_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(30)  NULL,          -- NULL for the initial PLACED entry
    to_status   VARCHAR(30)  NOT NULL,
    changed_by  BIGINT       NULL,          -- NULL = system
    note        VARCHAR(255),
    changed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_osh_order FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_osh_user  FOREIGN KEY (changed_by) REFERENCES users (id),
    INDEX idx_osh_order_changed (order_id, changed_at)
);

CREATE TABLE payments (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_id     BIGINT        NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    method       VARCHAR(20)   NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    provider_ref VARCHAR(100),
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payments_order UNIQUE (order_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_payments_amount CHECK (amount >= 0)
);

CREATE TABLE reviews (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL,
    restaurant_id     BIGINT       NOT NULL,
    restaurant_rating TINYINT      NOT NULL,
    partner_rating    TINYINT      NULL,
    comment           VARCHAR(1000),
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reviews_order UNIQUE (order_id),   -- one review per order, race-proof
    CONSTRAINT fk_reviews_order      FOREIGN KEY (order_id)      REFERENCES orders (id),
    CONSTRAINT fk_reviews_customer   FOREIGN KEY (customer_id)   REFERENCES users (id),
    CONSTRAINT fk_reviews_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_reviews_restaurant_rating CHECK (restaurant_rating BETWEEN 1 AND 5),
    CONSTRAINT chk_reviews_partner_rating CHECK (partner_rating IS NULL OR partner_rating BETWEEN 1 AND 5),
    INDEX idx_reviews_restaurant_created (restaurant_id, created_at)
);

CREATE TABLE notifications (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT       NOT NULL,
    order_id          BIGINT       NULL,
    type              VARCHAR(40)  NOT NULL,
    message           VARCHAR(500) NOT NULL,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user  FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_order FOREIGN KEY (order_id)          REFERENCES orders (id),
    INDEX idx_notifications_recipient_created (recipient_user_id, created_at)
);
