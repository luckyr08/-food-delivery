-- Bootstrap admin. Registration only creates customers, so the first admin must be seeded.
-- Login: admin@fooddelivery.com / Admin@123 (BCrypt, cost 10). Change it outside local dev.
INSERT INTO users (name, email, phone, password_hash, role, active, created_at, updated_at)
VALUES ('Platform Admin', 'admin@fooddelivery.com', NULL,
        '$2a$10$L3IDBXqmYoLjbupDy9hzKuRKvrhQmq5HLUW.2c4wM0AopehBgSzJ6',
        'ADMIN', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
