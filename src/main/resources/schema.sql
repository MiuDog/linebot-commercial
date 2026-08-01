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
    request_id                INTEGER NOT NULL,
    revision                  INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    quotation_no              TEXT UNIQUE,
    quotation_name            TEXT    NOT NULL,
    scheme_id                 INTEGER NOT NULL,
    template_id               INTEGER NOT NULL,
    selected_request_image_id INTEGER,
    customer_name             TEXT,
    customer_phone            TEXT,
    customer_email            TEXT,
    project_location          TEXT,
    currency                  TEXT    NOT NULL DEFAULT 'TWD',
    subtotal                  NUMERIC NOT NULL DEFAULT 0,
    tax_rate                  NUMERIC NOT NULL DEFAULT 0.05 CHECK (tax_rate >= 0),
    tax_amount                NUMERIC NOT NULL DEFAULT 0,
    total_amount              NUMERIC NOT NULL DEFAULT 0,
    status                    TEXT    NOT NULL DEFAULT 'DRAFT'
                                   CHECK (status IN ('DRAFT', 'EXPORTED', 'CANCELLED')),
    output_path               TEXT,
    created_at                TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exported_at               TEXT,
    UNIQUE (request_id, revision),
    FOREIGN KEY (request_id) REFERENCES quotation_request (id) ON DELETE RESTRICT,
    FOREIGN KEY (scheme_id) REFERENCES quotation_scheme (id) ON DELETE RESTRICT,
    FOREIGN KEY (template_id) REFERENCES quotation_template (id) ON DELETE RESTRICT,
    FOREIGN KEY (selected_request_image_id, request_id)
        REFERENCES quotation_request_image (id, request_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS quotation_line (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    quotation_id            INTEGER NOT NULL,
    line_number             INTEGER NOT NULL CHECK (line_number > 0),
    line_kind               TEXT    NOT NULL DEFAULT 'ITEM'
                                     CHECK (line_kind IN ('ITEM', 'ADJUSTMENT', 'SUMMARY')),
    visibility              TEXT    NOT NULL DEFAULT 'CUSTOMER'
                                     CHECK (visibility IN ('CUSTOMER', 'INTERNAL')),
    source_scheme_item_id   INTEGER,
    source_rule_id          INTEGER,
    item_code_snapshot      TEXT,
    item_name_snapshot      TEXT    NOT NULL,
    specification_snapshot TEXT,
    quantity                NUMERIC NOT NULL CHECK (quantity > 0),
    unit_snapshot           TEXT    NOT NULL,
    unit_price_snapshot     NUMERIC NOT NULL,
    line_amount             NUMERIC NOT NULL,
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

-- Seed only stable scheme and template coordinates. Product parameters arrive later.
INSERT INTO quotation_scheme (code, name, calculation_visibility)
VALUES
    ('CNS', 'CNS', 'DETAIL'),
    ('GENERAL', '一般架', 'DETAIL'),
    ('MARINE', '船用', 'SUMMARY_ONLY')
ON CONFLICT (code) DO NOTHING;

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
