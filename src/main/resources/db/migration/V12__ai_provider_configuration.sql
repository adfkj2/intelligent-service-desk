CREATE TABLE ai_provider_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    latest_version BIGINT NOT NULL DEFAULT 1,
    archived TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_provider_version (
    configuration_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    provider VARCHAR(24) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(128) NOT NULL,
    key_ciphertext TEXT NOT NULL,
    key_suffix VARCHAR(4) NOT NULL,
    currency VARCHAR(3) NULL,
    input_price DECIMAL(20,8) NULL,
    cached_input_price DECIMAL(20,8) NULL,
    output_price DECIMAL(20,8) NULL,
    test_status VARCHAR(16) NOT NULL DEFAULT 'UNTESTED',
    test_message VARCHAR(255) NULL,
    tested_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(configuration_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_runtime_setting (
    id INT NOT NULL PRIMARY KEY,
    mode VARCHAR(16) NOT NULL DEFAULT 'ENV',
    configuration_id BIGINT NULL,
    configuration_version BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO ai_runtime_setting(id,mode) VALUES(1,'ENV');

CREATE TABLE ai_configuration_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    configuration_id BIGINT NULL,
    configuration_version BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_config_audit_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_provider_balance (
    configuration_id BIGINT NOT NULL,
    configuration_version BIGINT NOT NULL,
    balance_json TEXT NULL,
    available TINYINT NULL,
    checked_at DATETIME NULL,
    attempted_at DATETIME NULL,
    last_error VARCHAR(255) NULL,
    PRIMARY KEY(configuration_id,configuration_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
