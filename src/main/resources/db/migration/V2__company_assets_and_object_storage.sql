-- 公司資產與物件儲存 metadata。
-- Flyway 規則：https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway

CREATE TABLE company_asset_set (
    id                BIGSERIAL PRIMARY KEY,
    company_id        TEXT NOT NULL,
    asset_set_version TEXT NOT NULL,
    schema_version    TEXT NOT NULL,
    status            TEXT NOT NULL CHECK (status IN ('STAGED', 'VALIDATED', 'APPROVED', 'ACTIVE', 'RETIRED')),
    manifest_hash     TEXT NOT NULL,
    created_by        TEXT NOT NULL,
    approved_by       TEXT,
    created_at        TEXT NOT NULL DEFAULT (CAST(CURRENT_TIMESTAMP AS TEXT)),
    approved_at       TEXT,
    activated_at      TEXT,
    UNIQUE (company_id, asset_set_version),
    UNIQUE (company_id, manifest_hash)
);

CREATE UNIQUE INDEX uq_company_asset_set_active
    ON company_asset_set (company_id)
    WHERE status = 'ACTIVE';

CREATE TABLE company_asset_object (
    id           BIGSERIAL PRIMARY KEY,
    asset_set_id BIGINT NOT NULL,
    purpose      TEXT NOT NULL,
    object_key   TEXT NOT NULL,
    object_version TEXT,
    content_type TEXT NOT NULL,
    file_size    BIGINT NOT NULL CHECK (file_size >= 0),
    content_hash TEXT NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (CAST(CURRENT_TIMESTAMP AS TEXT)),
    UNIQUE (asset_set_id, purpose),
    UNIQUE (asset_set_id, object_key),
    FOREIGN KEY (asset_set_id) REFERENCES company_asset_set (id) ON DELETE CASCADE
);

CREATE TABLE company_asset_activation (
    id                BIGSERIAL PRIMARY KEY,
    company_id        TEXT NOT NULL,
    previous_set_id   BIGINT,
    activated_set_id  BIGINT NOT NULL,
    action            TEXT NOT NULL CHECK (action IN ('ACTIVATE', 'ROLLBACK')),
    actor              TEXT NOT NULL,
    created_at         TEXT NOT NULL DEFAULT (CAST(CURRENT_TIMESTAMP AS TEXT)),
    FOREIGN KEY (previous_set_id) REFERENCES company_asset_set (id) ON DELETE RESTRICT,
    FOREIGN KEY (activated_set_id) REFERENCES company_asset_set (id) ON DELETE RESTRICT
);

CREATE TABLE storage_migration_ledger (
    id              BIGSERIAL PRIMARY KEY,
    company_id      TEXT NOT NULL,
    source_type     TEXT NOT NULL,
    source_identity TEXT NOT NULL,
    target_identity TEXT,
    content_hash    TEXT,
    status          TEXT NOT NULL CHECK (status IN ('DISCOVERED', 'COPIED', 'VERIFIED', 'FAILED')),
    error_code      TEXT,
    updated_at      TEXT NOT NULL DEFAULT (CAST(CURRENT_TIMESTAMP AS TEXT)),
    UNIQUE (company_id, source_type, source_identity)
);

ALTER TABLE asset
    ADD COLUMN company_id TEXT NOT NULL DEFAULT 'legacy',
    ADD COLUMN object_key TEXT,
    ADD COLUMN object_version TEXT,
    ADD COLUMN content_hash TEXT;

ALTER TABLE quotation
    ADD COLUMN asset_set_id BIGINT,
    ADD CONSTRAINT fk_quotation_asset_set
        FOREIGN KEY (asset_set_id) REFERENCES company_asset_set (id) ON DELETE RESTRICT;

ALTER TABLE quotation_file
    ADD COLUMN object_key TEXT,
    ADD COLUMN object_version TEXT;

CREATE INDEX idx_asset_company_object ON asset (company_id, object_key);
CREATE INDEX idx_quotation_asset_set ON quotation (asset_set_id);

