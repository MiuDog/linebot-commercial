# Exception

[← 回索引](index.md)

自訂例外。

---

## `AiExtractionException`

`dev.myudog.assetsmanagerlinebot.service.ai.AiExtractionException`

繼承 `RuntimeException`。

**職責**：表達「AI 資料提取失敗」這件事，並帶出可以直接講給使用者聽的原因。

| 建構子／方法 | 說明 |
|---|---|
| `AiExtractionException(String message, Throwable cause)` | 非欄位缺漏的失敗（呼叫失敗、回應格式錯誤等）。 |
| `AiExtractionException(String message, List<String> missingFields)` | 必要欄位缺漏的失敗。 |
| `List<String> missingFields()` | 缺少的欄位；非欄位缺漏時為空集合。 |
| `String userMessage()` | 組出可直接回覆給群組的說明文字。 |

### 兩種失敗，一個型別

差別只在 `missingFields`：

| 情境 | `missingFields` | `userMessage()` 輸出 |
|---|---|---|
| 未設定、連線失敗、狀態碼非 2xx、回應不是 JSON | 空 | 「資料提取失敗：{原因}」 |
| 解析成功但必要欄位缺漏 | 有值 | 「無法從圖片辨識出必要資料：{欄位}⋯請確認圖片清晰或改用其他角度重拍。」 |

需求指定「若無法提出特定資料則報錯」，第二種就是那個情境。

### 為什麼是 RuntimeException

提取失敗在 `CommandService` 是**唯一**的處理點——它會轉成群組訊息回覆給使用者。中間層（`QuotationService`）沒有任何有意義的處置方式，做成 checked exception 只會逼它加一層什麼事都不做的 `throws`。

### `userMessage()` 與 `getMessage()` 的分工

- `getMessage()`：給日誌看的，含截短後的原始回應片段。
- `userMessage()`：給群組成員看的，講「怎麼辦」而不是「哪裡壞了」。

回覆群組時**只用 `userMessage()`**——`getMessage()` 可能含端點網址或回應內容，不該外流到聊天室。
