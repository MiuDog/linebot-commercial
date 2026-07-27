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
