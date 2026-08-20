# Application

[← 回索引](index.md)

應用程式進入點。

---

## `LinebotCommercialApplication`

`dev.miudog.linebotcommercial.LinebotCommercialApplication`

**職責**：啟動 Spring 容器。本服務把 LINE 群組當成資產的收件與取件窗口——群組上傳的圖片落地到本機磁碟，SQLite 只保存指向該檔案的路徑與標籤，需要時再由群組指令查出來、透過對外端點貼回群組。

| 方法 | 說明 |
|---|---|
| `static void main(String[] args)` | 啟動 Spring Boot。`args` 直接交給框架處理。 |

**注意**：本類別刻意保持空殼。啟動期需要的初始化（例如建立 storage 目錄）放在 [`StorageConfig`](02-configuration.md#storageconfig)，因為那裡才能保證與 DataSource 的先後順序。
