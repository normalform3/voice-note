ALTER TABLE users
    DROP INDEX uk_users_email,
    CHANGE COLUMN email account VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN password_scheme VARCHAR(32) NOT NULL DEFAULT 'LEGACY_BCRYPT' AFTER password_hash,
    ADD UNIQUE KEY uk_users_account (account);
