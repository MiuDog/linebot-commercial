# Test

[← 回索引](index.md)

測試類別。全部不需要真實 LINE 憑證或 AI 金鑰，可留在一般 CI 流程。

執行：

```bash
./mvnw test
```

---

## `AssetsManagerLinebotApplicationTests`

`dev.myudog.assetsmanagerlinebot.AssetsManagerLinebotApplicationTests`

| 測試 | 驗證內容 |
|---|---|
| `contextLoads()` | Spring 容器能起來。 |

看似無用，實際擋掉最常見的部署事故：bean 循環依賴、缺少必填設定、DataSource 連不上。**`StorageConfig` 的目錄建立順序問題就是被這個測試抓到的。**

---

## `AssetServiceTest`

`dev.myudog.assetsmanagerlinebot.service.AssetServiceTest`

儲存路徑指向 `${java.io.tmpdir}/assets-manager-test`，不會污染專案目錄。

| 測試 | 驗證內容 |
|---|---|
| `chineseTagBecomesPhysicalFolderAndIsSearchable()` | 收錄 → 中文標籤 → 實體搬移 → 查詢的完整往返。確認中文資料夾在磁碟與 SQLite 兩端都不走樣，AND 語意正確，跨群組查不到。 |
| `duplicateWebhookEventIsNotIngestedTwice()` | 同一個 `messageId` 重送不會存成兩份。 |
| `pathTraversalCharactersInTagsAreStripped()` | `../../etc/passwd` → `etcpasswd`，`機房/設備` → `機房設備`，空白 → `未分類`。 |

第一個測試特別驗證 `onDisk.toString()` 含中文——這是**在 Windows 開發機與 Linux 容器上都必須成立**的條件，也是 Dockerfile 不能用 Alpine 的原因。

---

## `AiExtractionServiceTest`

`dev.myudog.assetsmanagerlinebot.service.ai.AiExtractionServiceTest`

以 JDK 內建的 `com.sun.net.httpserver.HttpServer` 假扮模型端點，用 `@DynamicPropertySource` 把隨機埠號注入 `app.ai.api-url`。

| 測試 | 驗證內容 |
|---|---|
| `parsesJsonResponseIntoFields()` | 正常回應解析成欄位；`"12 組"` 能取出數字 `12`。 |
| `stripsMarkdownCodeFenceAroundJson()` | 模型包上 ` ```json ` 區塊時仍能解析。 |
| `reportsMissingRequiredFields()` | 必要欄位為 null 時拋 `AiExtractionException`，且 `missingFields()` 正確。 |
| `reportsNonJsonResponse()` | 模型回自然語言時明確報錯。 |
| `reportsHttpError()` | 狀態碼 500 時報錯並帶出狀態碼。 |

**不需要真實金鑰**，所以能在 CI 跑。若改動 `buildRequestBody` 或 `extractContent` 去接其他廠商的 API，這組測試就是回歸網。

---

## 撰寫新測試的約定

1. **測試方法名用英文**。中文方法名可以執行，但 Windows 主控台的預設編碼會把失敗訊息印成亂碼，排查時看不出是哪個測試掛了。
2. **測試內容用中文沒問題**，而且該用——中文標籤、中文資料夾正是本專案要保證的行為。
3. **儲存路徑一律指向 `java.io.tmpdir`**，透過 `@TestPropertySource` 覆寫 `app.storage.path` 與 `spring.datasource.url`。
4. **不要在測試裡放真實憑證**。需要外部服務時，用 `HttpServer` 起一個假的。
