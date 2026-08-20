# Configuration

[← 回索引](index.md)

Spring bean 定義與基礎設施組裝。

---

## `StorageConfig`

`dev.miudog.linebotcommercial.config.StorageConfig`

**職責**：建立 SQLite 資料來源，並保證 storage 目錄先於連線存在。

| 方法 | 說明 |
|---|---|
| `DataSource dataSource(String storagePath, String jdbcUrl)` | 建立 storage 目錄後回傳設定好的 Hikari 連線池。缺目錄時拋 `IOException`。 |

### 為什麼不用 auto-configuration

SQLite **不會**幫忙建立資料庫檔案的父目錄。若 `app.storage.root`（`ASSETS_ROOT`）指向的目錄還不存在，連線會直接以 `path to '...' does not exist` 失敗，整個應用程式起不來——把 `ASSETS_ROOT` 指向一顆全新磁碟（例如 `F:/資產庫`）時必然踩到。

自行定義 bean 才能保證「先 `Files.createDirectories()`、再開連線」的順序；交給 auto-configuration 無法插入這個步驟。

### 連線池設定

| 設定 | 值 | 原因 |
|---|---|---|
| `maximumPoolSize` | `1` | SQLite 只允許單一寫入者，開多條連線只會換來 `SQLITE_BUSY`。 |
| `connectionInitSql` | `PRAGMA foreign_keys=ON` | SQLite 的外鍵約束預設關閉，且是**每條連線**的設定，必須在連線建立時開啟，`asset_tag` 的 `ON DELETE CASCADE` 才會生效。 |

因為連線池在這裡以程式碼設定，`application.properties` 內的 `spring.datasource.hikari.*` **不會生效**，不要在那邊加設定。
