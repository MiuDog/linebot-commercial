-- 資產索引：資料庫只存 metadata 與「指向」檔案的相對路徑，圖片本體留在磁碟
CREATE TABLE IF NOT EXISTS asset (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id    TEXT    NOT NULL UNIQUE,
    share_token   TEXT    NOT NULL UNIQUE,
    source_type   TEXT,
    source_id     TEXT,
    uploader_id   TEXT,
    file_path     TEXT    NOT NULL,
    content_type  TEXT,
    file_size     INTEGER,
    created_at    TEXT    NOT NULL
);

-- 檔案身分資料：用檔案系統識別碼優先追蹤 Explorer 改名／移動，雜湊作為跨平台備援
CREATE TABLE IF NOT EXISTS asset_file_identity (
    asset_id       INTEGER PRIMARY KEY,
    file_key       TEXT,
    content_hash   TEXT    NOT NULL,
    file_size      INTEGER NOT NULL,
    last_modified  INTEGER NOT NULL,
    updated_at     TEXT    NOT NULL,
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

-- 暫時離開資產根目錄的圖片只標記遺失，保留身分資料以便移回後復原。
DROP TABLE IF EXISTS asset_file_missing;

CREATE TABLE IF NOT EXISTS tag (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS asset_tag (
    asset_id INTEGER NOT NULL,
    tag_id   INTEGER NOT NULL,
    PRIMARY KEY (asset_id, tag_id),
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_asset_source ON asset (source_id);
CREATE INDEX IF NOT EXISTS idx_asset_tag_tag ON asset_tag (tag_id);

-- 尚未確認歸檔的 LINE 圖片。圖片本體暫存在 .pending，確認後才建立 asset。
CREATE TABLE IF NOT EXISTS pending_image (
    message_id    TEXT PRIMARY KEY,
    image_set_id  TEXT    NOT NULL,
    image_index   INTEGER NOT NULL CHECK (image_index > 0),
    image_total   INTEGER NOT NULL CHECK (image_total > 0),
    source_type   TEXT,
    source_id     TEXT    NOT NULL,
    uploader_id   TEXT,
    staging_path  TEXT    NOT NULL UNIQUE,
    content_type  TEXT,
    file_size     INTEGER NOT NULL,
    received_at   TEXT    NOT NULL,
    UNIQUE (source_id, image_set_id, image_index)
);

CREATE INDEX IF NOT EXISTS idx_pending_image_set
    ON pending_image (source_id, image_set_id, image_index);

-- 每張 LINE 圖片的抓取結果，用於完整回報成功、重複與失敗。
CREATE TABLE IF NOT EXISTS pending_image_fetch (
    source_id     TEXT    NOT NULL,
    image_set_id  TEXT    NOT NULL,
    image_index   INTEGER NOT NULL CHECK (image_index > 0),
    image_total   INTEGER NOT NULL CHECK (image_total > 0),
    message_id    TEXT    NOT NULL,
    status        TEXT    NOT NULL CHECK (status IN ('FETCHED', 'DUPLICATE', 'FAILED')),
    updated_at    TEXT    NOT NULL,
    PRIMARY KEY (source_id, image_set_id, image_index)
);

CREATE INDEX IF NOT EXISTS idx_pending_image_fetch_message
    ON pending_image_fetch (message_id);

CREATE TABLE IF NOT EXISTS pending_archive_confirmation (
    source_id     TEXT NOT NULL,
    requester_id  TEXT NOT NULL,
    image_set_id  TEXT NOT NULL,
    archive_date  TEXT NOT NULL,
    requested_at  TEXT NOT NULL,
    PRIMARY KEY (source_id, requester_id)
);

-- ===== Excel quotation master data =====

CREATE TABLE IF NOT EXISTS quotation_scheme (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    code                   TEXT    NOT NULL UNIQUE,
    name                   TEXT    NOT NULL,
    calculation_visibility TEXT    NOT NULL
                                   CHECK (calculation_visibility IN ('DETAIL', 'SUMMARY_ONLY')),
    is_active              INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at             TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quotation_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code         TEXT    NOT NULL UNIQUE,
    name         TEXT    NOT NULL,
    aliases_json TEXT    NOT NULL DEFAULT '[]'
                         CHECK (json_valid(aliases_json) AND json_type(aliases_json) = 'array'),
    is_active    INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quotation_rule (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    scheme_id       INTEGER NOT NULL,
    item_id         INTEGER,
    code            TEXT    NOT NULL,
    version         INTEGER NOT NULL CHECK (version > 0),
    rule_type       TEXT    NOT NULL
                            CHECK (rule_type IN ('DIRECT', 'FORMULA', 'LOOKUP', 'MANUAL')),
    definition_json TEXT    NOT NULL CHECK (json_valid(definition_json)),
    status          TEXT    NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    active_from     TEXT,
    active_to       TEXT,
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scheme_id, code, version),
    UNIQUE (id, scheme_id, item_id),
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT,
    FOREIGN KEY (item_id) REFERENCES quotation_item (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_scheme_item (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    scheme_id          INTEGER NOT NULL,
    item_id            INTEGER NOT NULL,
    specification      TEXT,
    unit               TEXT    NOT NULL,
    unit_price         NUMERIC NOT NULL CHECK (unit_price >= 0),
    remark             TEXT,
    display_order      INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    calculation_mode   TEXT    NOT NULL DEFAULT 'DIRECT'
                               CHECK (calculation_mode IN ('DIRECT', 'DERIVED', 'MANUAL')),
    default_rule_id    INTEGER,
    is_customer_visible INTEGER NOT NULL DEFAULT 1
                                CHECK (is_customer_visible IN (0, 1)),
    is_active          INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at         TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scheme_id, item_id),
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT,
    FOREIGN KEY (item_id) REFERENCES quotation_item (id) ON DELETE RESTRICT,
    FOREIGN KEY (default_rule_id, scheme_id, item_id)
        REFERENCES quotation_rule (id, scheme_id, item_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_quotation_scheme_item_scheme
    ON quotation_scheme_item (scheme_id, is_active, display_order);
CREATE INDEX IF NOT EXISTS idx_quotation_rule_active
    ON quotation_rule (scheme_id, status, active_from, active_to);

-- ===== Multi-message quotation drafts =====

CREATE TABLE IF NOT EXISTS quotation_draft (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_key             TEXT    NOT NULL UNIQUE,
    source_type           TEXT    NOT NULL,
    source_id             TEXT    NOT NULL,
    requester_id          TEXT,
    quotation_name        TEXT,
    company_name          TEXT,
    work_name             TEXT,
    contact_name          TEXT,
    customer_phone        TEXT,
    customer_email        TEXT,
    project_location      TEXT,
    scheme_id             INTEGER,
    status                TEXT    NOT NULL DEFAULT 'COLLECTING_BASE_INFO'
                                  CHECK (status IN (
                                      'COLLECTING_BASE_INFO',
                                      'COLLECTING_ITEMS',
                                      'AWAITING_IMAGE',
                                      'READY_FOR_PREVIEW',
                                      'AWAITING_CONFIRMATION',
                                      'CONFIRMED',
                                      'CANCELLED',
                                      'EXPIRED'
                                  )),
    image_declined        INTEGER NOT NULL DEFAULT 0 CHECK (image_declined IN (0, 1)),
    confirmation_revision INTEGER,
    revision              INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    expires_at            TEXT,
    confirmed_at          TEXT,
    cancelled_at          TEXT,
    created_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_draft_message (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_id         INTEGER NOT NULL,
    message_id       TEXT    NOT NULL UNIQUE,
    message_type     TEXT    NOT NULL CHECK (message_type IN ('TEXT', 'IMAGE', 'FILE')),
    raw_text         TEXT,
    ai_response_json TEXT    CHECK (ai_response_json IS NULL OR json_valid(ai_response_json)),
    received_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS quotation_draft_item (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_id               INTEGER NOT NULL,
    item_kind              TEXT    NOT NULL DEFAULT 'STANDARD'
                                   CHECK (item_kind IN ('STANDARD', 'CUSTOM', 'SUMMARY')),
    item_id                 INTEGER,
    client_item_id          TEXT,
    item_code_snapshot      TEXT,
    item_name_snapshot      TEXT,
    specification_snapshot TEXT,
    quantity                NUMERIC CHECK (quantity IS NULL OR quantity > 0),
    unit_snapshot           TEXT,
    unit_price_snapshot     NUMERIC CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0),
    remark_snapshot         TEXT,
    source_text             TEXT,
    matched_name            TEXT,
    confidence              NUMERIC CHECK (confidence BETWEEN 0 AND 1),
    is_removed              INTEGER NOT NULL DEFAULT 0 CHECK (is_removed IN (0, 1)),
    display_order           INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at              TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (item_kind = 'STANDARD' AND item_id IS NOT NULL AND client_item_id IS NULL)
        OR (item_kind IN ('CUSTOM', 'SUMMARY') AND item_id IS NULL AND client_item_id IS NOT NULL)
    ),
    UNIQUE (draft_id, item_id),
    UNIQUE (draft_id, client_item_id),
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE,
    FOREIGN KEY (item_id) REFERENCES quotation_item (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_draft_field (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_id            INTEGER NOT NULL,
    field_key           TEXT    NOT NULL,
    field_value         TEXT,
    source_message_id   TEXT,
    source_text         TEXT,
    confidence          NUMERIC CHECK (confidence BETWEEN 0 AND 1),
    confirmation_status TEXT    NOT NULL DEFAULT 'EXTRACTED'
                                CHECK (confirmation_status IN ('EXTRACTED', 'CONFIRMED', 'REJECTED')),
    updated_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (draft_id, field_key),
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS quotation_draft_image (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_id              INTEGER NOT NULL,
    message_id            TEXT    NOT NULL,
    candidate_order       INTEGER NOT NULL DEFAULT 0 CHECK (candidate_order >= 0),
    distinctiveness_score NUMERIC CHECK (distinctiveness_score BETWEEN 0 AND 1),
    quality_score         NUMERIC CHECK (quality_score BETWEEN 0 AND 1),
    selection_reason      TEXT,
    is_selected           INTEGER NOT NULL DEFAULT 0 CHECK (is_selected IN (0, 1)),
    created_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (draft_id, message_id),
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_draft_selected_image
    ON quotation_draft_image (draft_id)
    WHERE is_selected = 1;

CREATE TABLE IF NOT EXISTS quotation_event_receipt (
    event_id       TEXT PRIMARY KEY,
    draft_id       INTEGER,
    event_type     TEXT NOT NULL CHECK (event_type IN ('MESSAGE', 'POSTBACK')),
    received_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at   TEXT,
    lease_until    TEXT,
    attempt_count  INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    result_status  TEXT NOT NULL DEFAULT 'RECEIVED'
                        CHECK (result_status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_quotation_draft_owner
    ON quotation_draft (source_id, requester_id, status, updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_draft_active_user
    ON quotation_draft (source_id)
    WHERE source_type = 'user'
        AND status IN (
            'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
            'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION'
        );
CREATE INDEX IF NOT EXISTS idx_quotation_draft_message_draft
    ON quotation_draft_message (draft_id, received_at);
CREATE INDEX IF NOT EXISTS idx_quotation_draft_image_draft
    ON quotation_draft_image (draft_id, candidate_order);

-- ===== Local administration and LINE rich menu =====

CREATE TABLE IF NOT EXISTS line_rich_menu_action (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    action_key    TEXT    NOT NULL UNIQUE,
    label         TEXT    NOT NULL,
    action_type   TEXT    NOT NULL CHECK (action_type IN ('MESSAGE', 'URI')),
    action_data   TEXT    NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    is_active     INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    updated_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    action        TEXT    NOT NULL,
    entity_type   TEXT    NOT NULL,
    entity_id     TEXT,
    summary_json  TEXT    NOT NULL CHECK (json_valid(summary_json)),
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created
    ON admin_audit_log (created_at DESC);

-- ===== Excel template coordinates =====

CREATE TABLE IF NOT EXISTS quotation_template (
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    template_key              TEXT    NOT NULL UNIQUE,
    scheme_id                 INTEGER NOT NULL,
    version                   INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    workbook_path             TEXT,
    sheet_name                TEXT    NOT NULL,
    summary_only              INTEGER NOT NULL DEFAULT 0 CHECK (summary_only IN (0, 1)),
    detail_first_row          INTEGER NOT NULL CHECK (detail_first_row > 0),
    detail_last_row           INTEGER NOT NULL CHECK (detail_last_row >= detail_first_row),
    column_mapping_json       TEXT    NOT NULL CHECK (json_valid(column_mapping_json)),
    subtotal_cell             TEXT    NOT NULL,
    pre_tax_cell              TEXT    NOT NULL,
    tax_cell                  TEXT    NOT NULL,
    total_cell                TEXT    NOT NULL,
    tax_rate                  NUMERIC NOT NULL DEFAULT 0.05 CHECK (tax_rate >= 0),
    image_placement           TEXT    NOT NULL DEFAULT 'UNUSED_DETAIL_ROWS'
                                      CHECK (image_placement IN ('UNUSED_DETAIL_ROWS', 'FIXED_RANGE', 'NONE')),
    image_first_column        TEXT,
    image_last_column         TEXT,
    image_minimum_unused_rows INTEGER NOT NULL DEFAULT 4
                                      CHECK (image_minimum_unused_rows >= 0),
    image_fit                 TEXT    NOT NULL DEFAULT 'CONTAIN'
                                      CHECK (image_fit IN ('CONTAIN')),
    is_active                 INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at                TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scheme_id, version),
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_template_active_scheme
    ON quotation_template (scheme_id)
    WHERE is_active = 1;

-- ===== AI parsing requests and candidate images =====

CREATE TABLE IF NOT EXISTS quotation_request (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    source_type           TEXT    NOT NULL,
    source_id             TEXT    NOT NULL,
    requester_id          TEXT,
    command_message_id    TEXT    NOT NULL UNIQUE,
    raw_instruction       TEXT    NOT NULL,
    contract_version      TEXT    NOT NULL DEFAULT '1.0',
    scheme_id             INTEGER,
    scheme_confidence     NUMERIC CHECK (scheme_confidence BETWEEN 0 AND 1),
    ai_response_json      TEXT    CHECK (ai_response_json IS NULL OR json_valid(ai_response_json)),
    status                TEXT    NOT NULL DEFAULT 'RECEIVED'
                               CHECK (status IN (
                                   'RECEIVED',
                                   'PARSED',
                                   'REVIEW_REQUIRED',
                                   'CALCULATED',
                                   'EXPORTED',
                                   'FAILED'
                               )),
    error_message         TEXT,
    created_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_request_image (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id            INTEGER NOT NULL,
    asset_id              INTEGER,
    message_id            TEXT    NOT NULL,
    candidate_order       INTEGER NOT NULL DEFAULT 0 CHECK (candidate_order >= 0),
    distinctiveness_score NUMERIC CHECK (distinctiveness_score BETWEEN 0 AND 1),
    selection_reason      TEXT,
    is_selected           INTEGER NOT NULL DEFAULT 0 CHECK (is_selected IN (0, 1)),
    created_at            TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_id, message_id),
    UNIQUE (id, request_id),
    FOREIGN KEY (request_id) REFERENCES quotation_request (id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_quotation_request_source
    ON quotation_request (source_id, created_at);
CREATE INDEX IF NOT EXISTS idx_quotation_request_image_request
    ON quotation_request_image (request_id, candidate_order);
CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_request_selected_image
    ON quotation_request_image (request_id)
    WHERE is_selected = 1;

-- ===== Immutable quotation snapshots =====

CREATE TABLE IF NOT EXISTS quotation (
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id                INTEGER,
    draft_id                  INTEGER UNIQUE,
    revision                  INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    quotation_no              TEXT UNIQUE,
    quotation_name            TEXT    NOT NULL,
    sequence_date             TEXT,
    sequence_number           INTEGER CHECK (sequence_number IS NULL OR sequence_number >= 1),
    company_name              TEXT,
    work_name                 TEXT,
    quotation_date            TEXT,
    valid_until               TEXT,
    scheme_id                 INTEGER NOT NULL,
    template_id               INTEGER NOT NULL,
    selected_request_image_id INTEGER,
    customer_name             TEXT,
    customer_phone            TEXT,
    customer_fax              TEXT,
    customer_email            TEXT,
    contact_name              TEXT,
    project_location          TEXT,
    sales_representative      TEXT,
    additional_header         TEXT,
    currency                  TEXT    NOT NULL DEFAULT 'TWD',
    subtotal                  NUMERIC NOT NULL DEFAULT 0,
    tax_rate                  NUMERIC NOT NULL DEFAULT 0.05 CHECK (tax_rate >= 0),
    tax_amount                NUMERIC NOT NULL DEFAULT 0,
    total_amount              NUMERIC NOT NULL DEFAULT 0,
    status                    TEXT    NOT NULL DEFAULT 'CONFIRMED'
                                   CHECK (status IN (
                                       'CONFIRMED',
                                       'GENERATING_EXCEL',
                                       'GENERATING_PDF',
                                       'PDF_FAILED',
                                       'READY',
                                       'SENDING',
                                       'SENT',
                                       'CANCELLED',
                                       'FAILED'
                                   )),
    output_path               TEXT,
    created_at                TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exported_at               TEXT,
    UNIQUE (request_id, revision),
    UNIQUE (sequence_date, sequence_number),
    FOREIGN KEY (request_id) REFERENCES quotation_request (id) ON DELETE RESTRICT,
    FOREIGN KEY (draft_id) REFERENCES quotation_draft (id) ON DELETE RESTRICT,
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT,
    FOREIGN KEY (template_id) REFERENCES quotation_template (id) ON DELETE RESTRICT,
    FOREIGN KEY (selected_request_image_id, request_id)
        REFERENCES quotation_request_image (id, request_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_line (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id            INTEGER NOT NULL,
    line_number             INTEGER NOT NULL CHECK (line_number > 0),
    line_kind               TEXT    NOT NULL DEFAULT 'STANDARD'
                                     CHECK (line_kind IN ('STANDARD', 'CUSTOM', 'ADJUSTMENT', 'SUMMARY')),
    visibility              TEXT    NOT NULL DEFAULT 'CUSTOMER'
                                     CHECK (visibility IN ('CUSTOMER', 'INTERNAL')),
    source_scheme_item_id   INTEGER,
    source_rule_id          INTEGER,
    item_code_snapshot      TEXT,
    item_name_snapshot      TEXT    NOT NULL,
    specification_snapshot TEXT,
    quantity                NUMERIC CHECK (quantity IS NULL OR quantity > 0),
    unit_snapshot           TEXT    NOT NULL,
    unit_price_snapshot     NUMERIC NOT NULL,
    line_amount             NUMERIC,
    remark_snapshot         TEXT,
    source_text             TEXT,
    calculation_detail_json TEXT
                            CHECK (
                                calculation_detail_json IS NULL
                                OR json_valid(calculation_detail_json)
                            ),
    created_at              TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (quotation_id, line_number),
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE,
    FOREIGN KEY (source_scheme_item_id)
        REFERENCES quotation_scheme_item (id) ON DELETE SET NULL,
    FOREIGN KEY (source_rule_id) REFERENCES quotation_rule (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_quotation_request_revision
    ON quotation (request_id, revision);
CREATE INDEX IF NOT EXISTS idx_quotation_line_quotation
    ON quotation_line (quotation_id, line_number);

CREATE TABLE IF NOT EXISTS quotation_daily_sequence (
    sequence_date  TEXT PRIMARY KEY,
    last_sequence INTEGER NOT NULL CHECK (last_sequence >= 0),
    updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quotation_asset (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id          INTEGER NOT NULL,
    asset_id              INTEGER NOT NULL,
    candidate_order       INTEGER NOT NULL DEFAULT 0 CHECK (candidate_order >= 0),
    distinctiveness_score NUMERIC CHECK (distinctiveness_score BETWEEN 0 AND 1),
    quality_score         NUMERIC CHECK (quality_score BETWEEN 0 AND 1),
    selection_reason      TEXT,
    is_selected           INTEGER NOT NULL DEFAULT 0 CHECK (is_selected IN (0, 1)),
    created_at            TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (quotation_id, asset_id),
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_selected_asset
    ON quotation_asset (quotation_id)
    WHERE is_selected = 1;

CREATE TABLE IF NOT EXISTS quotation_file (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id   INTEGER NOT NULL,
    file_kind      TEXT    NOT NULL CHECK (file_kind IN ('XLSX', 'PDF')),
    relative_path  TEXT,
    content_type   TEXT    NOT NULL,
    content_hash   TEXT,
    file_size      INTEGER CHECK (file_size IS NULL OR file_size >= 0),
    status         TEXT    NOT NULL DEFAULT 'PENDING'
                           CHECK (status IN ('PENDING', 'GENERATING', 'READY', 'FAILED')),
    error_message  TEXT,
    created_at     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (quotation_id, file_kind),
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS quotation_download_token (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id   INTEGER NOT NULL,
    file_id        INTEGER,
    asset_id       INTEGER,
    purpose        TEXT    NOT NULL CHECK (purpose IN ('PDF', 'IMAGE_ORIGINAL', 'IMAGE_THUMBNAIL')),
    token_hash     TEXT    NOT NULL UNIQUE,
    expires_at     TEXT    NOT NULL,
    revoked_at     TEXT,
    created_at     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (purpose = 'PDF' AND file_id IS NOT NULL AND asset_id IS NULL)
        OR (purpose IN ('IMAGE_ORIGINAL', 'IMAGE_THUMBNAIL') AND asset_id IS NOT NULL AND file_id IS NULL)
    ),
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES quotation_file (id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_quotation_download_expiry
    ON quotation_download_token (expires_at, revoked_at);

CREATE TABLE IF NOT EXISTS quotation_reply_outbox (
    event_id        TEXT PRIMARY KEY,
    destination_id  TEXT NOT NULL,
    messages_json   TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'SENT')),
    attempt_count   INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_quotation_reply_outbox_pending
    ON quotation_reply_outbox (status, updated_at);

CREATE TABLE IF NOT EXISTS quotation_generation_job (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id        INTEGER NOT NULL UNIQUE,
    status              TEXT    NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'RUNNING', 'FAILED', 'DONE')),
    destination_id      TEXT    NOT NULL,
    correlation_id      TEXT    NOT NULL,
    lease_owner         TEXT,
    lease_until         TEXT,
    attempt_count       INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at     TEXT    NOT NULL,
    last_error_code     TEXT,
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,
    completed_at        TEXT,
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_quotation_generation_job_due
    ON quotation_generation_job (status, next_attempt_at, lease_until, id);

CREATE TABLE IF NOT EXISTS quotation_delivery_attempt (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id       INTEGER NOT NULL,
    destination_type   TEXT    NOT NULL CHECK (destination_type IN ('LINE_USER')),
    destination_id     TEXT    NOT NULL,
    delivery_kind      TEXT    NOT NULL CHECK (delivery_kind IN ('PREVIEW', 'FINAL')),
    status             TEXT    NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    provider_message_id TEXT,
    error_message      TEXT,
    attempted_at       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TEXT,
    FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_quotation_delivery_retry
    ON quotation_delivery_attempt (quotation_id, status, attempted_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_delivery_active
    ON quotation_delivery_attempt (
        quotation_id,
        destination_type,
        destination_id,
        delivery_kind
    )
    WHERE status IN ('PENDING', 'SENDING', 'SENT');

-- Seed only stable scheme and known template coordinates. Product parameters arrive later.
INSERT INTO quotation_scheme (code, name, calculation_visibility)
VALUES
    ('CNS', 'CNS', 'DETAIL'),
    ('GENERAL', '一般架', 'DETAIL'),
    ('MARINE', '船用', 'SUMMARY_ONLY'),
    ('BLANK', '空白', 'DETAIL'),
    ('SALES', '銷售報價單', 'DETAIL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO line_rich_menu_action (
    action_key,
    label,
    action_type,
    action_data,
    display_order
)
VALUES
    ('CREATE_QUOTATION', '建立報價', 'MESSAGE', '建立報價', 10),
    ('MY_DRAFTS', '我的草稿', 'MESSAGE', '我的草稿', 20),
    ('SELECT_SCHEME', '報價類型', 'MESSAGE', '選擇報價類型', 30),
    ('UPLOAD_IMAGE', '上傳圖片', 'MESSAGE', '上傳圖片', 40),
    ('HELP', '使用說明', 'MESSAGE', '使用說明', 50),
    ('CANCEL', '取消操作', 'MESSAGE', '取消操作', 60)
ON CONFLICT (action_key) DO NOTHING;

INSERT INTO quotation_template (
    template_key,
    scheme_id,
    sheet_name,
    summary_only,
    detail_first_row,
    detail_last_row,
    column_mapping_json,
    subtotal_cell,
    pre_tax_cell,
    tax_cell,
    total_cell,
    image_first_column,
    image_last_column
)
SELECT
    'CNS_V1',
    id,
    'CNS',
    0,
    11,
    28,
    '{"lineNumber":"A","itemName":"B","specification":"C","quantity":"D","unit":"E","unitPrice":"F","lineAmount":"G","remark":"H"}',
    'G29',
    'G30',
    'G31',
    'G32',
    'A',
    'C'
FROM quotation_scheme
WHERE code = 'CNS'
ON CONFLICT (template_key) DO NOTHING;

INSERT INTO quotation_template (
    template_key,
    scheme_id,
    sheet_name,
    summary_only,
    detail_first_row,
    detail_last_row,
    column_mapping_json,
    subtotal_cell,
    pre_tax_cell,
    tax_cell,
    total_cell,
    image_first_column,
    image_last_column
)
SELECT
    'GENERAL_V1',
    id,
    '一般架',
    0,
    11,
    30,
    '{"lineNumber":"A","itemName":"B","specification":"C","quantity":"D","unit":"E","unitPrice":"F","lineAmount":"G","remark":"H"}',
    'G31',
    'G32',
    'G33',
    'G34',
    'A',
    'C'
FROM quotation_scheme
WHERE code = 'GENERAL'
ON CONFLICT (template_key) DO NOTHING;

INSERT INTO quotation_template (
    template_key,
    scheme_id,
    sheet_name,
    summary_only,
    detail_first_row,
    detail_last_row,
    column_mapping_json,
    subtotal_cell,
    pre_tax_cell,
    tax_cell,
    total_cell,
    image_first_column,
    image_last_column
)
SELECT
    'MARINE_V1',
    id,
    '船用',
    1,
    11,
    23,
    '{"lineNumber":"A","itemName":"B","specification":"C","quantity":"D","unit":"E","unitPrice":"F","lineAmount":"G","remark":"H"}',
    'G24',
    'G25',
    'G26',
    'G27',
    'A',
    'C'
FROM quotation_scheme
WHERE code = 'MARINE'
ON CONFLICT (template_key) DO NOTHING;

-- 五種範本已拆成單工作表活頁簿；CNS 的明細列依實際上傳檔更新為 21 列。
UPDATE quotation_template
SET workbook_path = 'outputs/excel-templates/quotation-template-CNS.xlsx',
    detail_last_row = 31,
    subtotal_cell = 'G32',
    pre_tax_cell = 'G33',
    tax_cell = 'G34',
    total_cell = 'G35',
    updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'CNS_V1';

UPDATE quotation_template
SET workbook_path = CASE template_key
        WHEN 'GENERAL_V1' THEN 'outputs/excel-templates/quotation-template-GENERAL.xlsx'
        WHEN 'MARINE_V1' THEN 'outputs/excel-templates/quotation-template-MARINE.xlsx'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE template_key IN ('GENERAL_V1', 'MARINE_V1');

INSERT INTO quotation_template (
    template_key,
    scheme_id,
    workbook_path,
    sheet_name,
    summary_only,
    detail_first_row,
    detail_last_row,
    column_mapping_json,
    subtotal_cell,
    pre_tax_cell,
    tax_cell,
    total_cell,
    image_first_column,
    image_last_column
)
SELECT
    'BLANK_V1',
    id,
    'outputs/excel-templates/quotation-template-BLANK.xlsx',
    '空白',
    0,
    11,
    28,
    '{"lineNumber":"A","itemName":"B","specification":"C","quantity":"D","unit":"E","unitPrice":"F","lineAmount":"G","remark":"H"}',
    'G29',
    'G30',
    'G31',
    'G32',
    'A',
    'C'
FROM quotation_scheme
WHERE code = 'BLANK'
ON CONFLICT (template_key) DO NOTHING;

INSERT INTO quotation_template (
    template_key,
    scheme_id,
    workbook_path,
    sheet_name,
    summary_only,
    detail_first_row,
    detail_last_row,
    column_mapping_json,
    subtotal_cell,
    pre_tax_cell,
    tax_cell,
    total_cell,
    image_first_column,
    image_last_column
)
SELECT
    'SALES_V1',
    id,
    'outputs/excel-templates/quotation-template-SALES.xlsx',
    '銷售報價單(報價單號前會多一個S)',
    0,
    11,
    28,
    '{"lineNumber":"A","itemName":"B","specification":"C","quantity":"D","unit":"E","unitPrice":"F","lineAmount":"G","remark":"H"}',
    'G29',
    'G30',
    'G31',
    'G32',
    'A',
    'C'
FROM quotation_scheme
WHERE code = 'SALES'
ON CONFLICT (template_key) DO NOTHING;

-- Excel 品項主檔：首次匯入採 INSERT OR IGNORE，日後網頁修改不會在重啟時被覆蓋。
INSERT OR IGNORE INTO quotation_item (code, name, aliases_json, is_active)
VALUES
    ('EXTERNAL_SCAFFOLD', '外部鷹架', '[]', 1),
    ('CROSS_BRACE', '交叉拉桿', '[]', 1),
    ('MIDDLE_RAIL', '中欄杆', '[]', 1),
    ('SAFETY_LIFELINE', '安全母索', '[]', 1),
    ('DUST_NET', '防塵網', '[]', 1),
    ('CANVAS', '帆布', '[]', 1),
    ('STAIRCASE', '樓梯', '[]', 1),
    ('EXTENSION_FRAME', '延伸架', '[]', 1),
    ('FALL_PROTECTION_NET', '防墜網', '[]', 1),
    ('WALL_TIE_ROD', '壁連桿', '[]', 1),
    ('SAFETY_DIAGONAL_BRACE', '安全斜撐', '[]', 1),
    ('LEADING_HANDRAIL', '扶手先行', '[]', 1),
    ('SHORT_TRIANGLE_BRACKET', '三角架(短)', '[]', 1),
    ('STEEL_CLAMP_BOARD', '鐵夾板', '[]', 1),
    ('TOE_BOARD', '腳趾板', '[]', 1),
    ('EIGHT_SHAPE_COUPLER', '八字扣', '[]', 1),
    ('SWIVEL_COUPLER', '活扣', '[]', 1),
    ('G_CLAMP', 'G夾', '[]', 1),
    ('SCAFFOLD_DAY_LABOR', '鷹架點工', '[]', 1),
    ('STRUCTURAL_CALCULATION_CERTIFICATION', '結構計算(含技師簽證)', '[]', 1),
    ('ELEVATOR_GATE', '電梯閘門', '[]', 1);

WITH excel_master (scheme_code, item_code, specification, unit, unit_price, remark, display_order) AS (
    VALUES
    ('CNS', 'EXTERNAL_SCAFFOLD', '(CNS)', 'm2', 220, '(實做實算)', 1),
    ('CNS', 'CROSS_BRACE', '(CNS)', 'm2', 25, '(實做實算)', 2),
    ('CNS', 'MIDDLE_RAIL', '', 'm', 25, '(實做實算)', 3),
    ('CNS', 'SAFETY_LIFELINE', '', 'm', 30, '(實做實算)', 4),
    ('CNS', 'DUST_NET', '9針', 'm2', 40, '(實做實算)', 5),
    ('CNS', 'CANVAS', '', 'm2', 70, '(實做實算)', 6),
    ('CNS', 'STAIRCASE', '30cm寬/內掛式', '支', 700, '(實做實算)', 7),
    ('CNS', 'EXTENSION_FRAME', '含踏板', 'm', 350, '(實做實算)', 8),
    ('CNS', 'FALL_PROTECTION_NET', '不含托架', 'm', 120, '(實做實算)', 9),
    ('CNS', 'WALL_TIE_ROD', '壁連座/C型夾', '支', 350, '(實做實算)', 10),
    ('CNS', 'SAFETY_DIAGONAL_BRACE', '打底層用/標準型', '支', 800, '(實做實算)', 11),
    ('CNS', 'LEADING_HANDRAIL', '單側搭拆', 'm2', 50, '(實做實算)', 12),
    ('CNS', 'SHORT_TRIANGLE_BRACKET', '含1.5m以下', '支', 400, '(實做實算)', 13),
    ('CNS', 'STEEL_CLAMP_BOARD', '', 'm', 800, '(實做實算)', 14),
    ('CNS', 'TOE_BOARD', '', 'm', 200, '(實做實算)', 15),
    ('CNS', 'EIGHT_SHAPE_COUPLER', '', 'pcs', 30, '(實做實算)', 16),
    ('CNS', 'SWIVEL_COUPLER', '', 'pcs', 50, '(實做實算)', 17),
    ('CNS', 'G_CLAMP', '', 'pcs', 80, '(實做實算)', 18),
    ('CNS', 'SCAFFOLD_DAY_LABOR', '', '工', 3500, '(實做實算)', 19),
    ('CNS', 'STRUCTURAL_CALCULATION_CERTIFICATION', '', '式', 35000, '(實做實算)', 20),
    ('CNS', 'ELEVATOR_GATE', '', '片', 2000, '(實做實算)', 21),
    ('GENERAL', 'EXTERNAL_SCAFFOLD', '(一般料)', 'm2', 180, '(實做實算)', 1),
    ('GENERAL', 'CROSS_BRACE', '(一般料)', 'm2', 20, '(實做實算)', 2),
    ('GENERAL', 'MIDDLE_RAIL', '單側', 'm', 25, '(實做實算)', 3),
    ('GENERAL', 'SAFETY_LIFELINE', '', 'm', 25, '(實做實算)', 4),
    ('GENERAL', 'DUST_NET', '9針', 'm2', 45, '(實做實算)', 5),
    ('GENERAL', 'CANVAS', '9針', 'm2', 80, '(實做實算)', 6),
    ('GENERAL', 'STAIRCASE', '30cm寬/內掛式', '支', 1000, '(實做實算)', 7),
    ('GENERAL', 'EXTENSION_FRAME', '含踏板', 'm', 350, '(實做實算)', 8),
    ('GENERAL', 'FALL_PROTECTION_NET', '不含托架', 'm', 150, '(實做實算)', 9),
    ('GENERAL', 'WALL_TIE_ROD', '壁連座/C型夾', '支', 350, '(實做實算)', 10),
    ('GENERAL', 'SAFETY_DIAGONAL_BRACE', '打底層用/標準型', '支', 800, '(實做實算)', 11),
    ('GENERAL', 'LEADING_HANDRAIL', '單側搭拆', 'm2', 50, '(實做實算)', 12),
    ('GENERAL', 'SHORT_TRIANGLE_BRACKET', '含1.5m以下', '支', 400, '(實做實算)', 13),
    ('GENERAL', 'STEEL_CLAMP_BOARD', '', 'm', 700, '(實做實算)', 14),
    ('GENERAL', 'TOE_BOARD', '', 'm', 140, '(實做實算)', 15),
    ('GENERAL', 'EIGHT_SHAPE_COUPLER', '', 'pcs', 30, '(實做實算)', 16),
    ('GENERAL', 'SWIVEL_COUPLER', '', 'pcs', 50, '(實做實算)', 17),
    ('GENERAL', 'G_CLAMP', '', 'pcs', 80, '(實做實算)', 18),
    ('GENERAL', 'SCAFFOLD_DAY_LABOR', '', '工', 3500, '(實做實算)', 19),
    ('GENERAL', 'STRUCTURAL_CALCULATION_CERTIFICATION', '', '式', 35000, '(實做實算)', 20)
)
INSERT OR IGNORE INTO quotation_scheme_item (
    scheme_id, item_id, specification, unit, unit_price, remark, display_order,
    calculation_mode, is_customer_visible, is_active
)
SELECT
    s.id, i.id, m.specification, m.unit, m.unit_price, m.remark, m.display_order,
    'DIRECT', 1, 1
FROM excel_master m
JOIN quotation_scheme s ON s.code = m.scheme_code
JOIN quotation_item i ON i.code = m.item_code;
