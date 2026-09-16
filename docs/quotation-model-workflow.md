# 多模型報價 workflow

## 相容性與範圍

`AI_WORKFLOW_ENABLED=false` 是預設值：自然語言仍使用原來的單模型與最多一次修復。CSV、草稿合併、單價與合計、確認、XLSX／PDF、交付重試均保留原流程。開啟後只替換 AI 抽取步驟，不讓模型直接計價或確認報價。

1. CSV 直接解析，零模型呼叫。
2. 沒有圖片的文字呼叫 TEXT。
3. 有圖片時先呼叫 VISION，只取得每張圖的原文與內容描述，再將觀察資料交給 TEXT；TEXT 不重收圖片。
4. 所有結果通過原有業務驗證。契約錯誤或候選 CONFLICT 才呼叫 ESCALATION 一次。
5. 缺少資料維持補問；拒絕、截斷、認證或 HTTP 失敗不自動切模型。

角色都使用支援 strict JSON Schema 的 **OpenAI-compatible Chat Completions**。這不是對任意廠商原生 API 的轉接器，端點必須相容。VISION 必須支援圖片。

## 設定

保留原有 `AI_API_URL`、`AI_MODEL`、`secrets/ai-api-key` 作為預設。於 `.env` 設定：

```dotenv
AI_WORKFLOW_ENABLED=true
AI_TEXT_MODEL=填入文字抽取模型名稱
AI_VISION_MODEL=填入支援圖片的模型名稱
AI_ESCALATION_MODEL=填入較強的模型名稱
AI_WORKFLOW_MAX_CALLS=3
AI_WORKFLOW_MAX_OUTPUT_TOKENS=12000
AI_WORKFLOW_MAX_TOTAL_TOKENS=32000
AI_WORKFLOW_TIMEOUT_SECONDS=120
```

以上三個模型名稱是說明用佔位字，需換成帳號可使用的實際名稱。角色 model 留空時沿用 `AI_MODEL`，可以先用同一模型驗證流程，再逐步替換角色。

| 角色 | 選用端點 | 獨立金鑰檔 |
| --- | --- | --- |
| TEXT | AI_TEXT_API_URL | secrets/ai-text-api-key |
| VISION | AI_VISION_API_URL | secrets/ai-vision-api-key |
| ESCALATION | AI_ESCALATION_API_URL | secrets/ai-escalation-api-key |

端點留空時沿用基底。**端點不同時必須提供該角色金鑰**，不會將基底金鑰送去另一端點；相同端點也可指定獨立金鑰。Docker 將 secrets 目錄唯讀掛載至 `/run/ai-secrets`，角色檔案可以不存在；不要建立同名資料夾。Windows 控制台在 workflow 啟用且角色端點不同時會要求對應 Secret。

每個角色另有 `AI_{角色}_MAX_COMPLETION_TOKENS`（預設 4000，256–32000）與 `AI_{角色}_TIMEOUT_SECONDS`（預設 60，1–300）。修改 `.env` 後執行：

```text
docker compose up --build -d --wait
```

直接啟動 Java 或 Kubernetes 時，可用 `AI_TEXT_API_KEY`、`AI_VISION_API_KEY`、`AI_ESCALATION_API_KEY` 從 Secret 注入；不要把金鑰寫到 ConfigMap。

## 配額的精確意義

- 呼叫上限為 1–3；設定 1 時只能處理單步文字，有圖片時可能在 VISION 後停止。
- 累計輸出上限是**分配給請求的輸出 token 總和**。即使模型提前結束，也保守扣除該次配額；後續模型的輸出上限會縮到剩餘額度。一般三步預設各分配 4000。
- total token 門檻根據供應商 `usage.total_tokens`，包含輸入及輸出；達到門檻後不再追加呼叫。**這不是精確的帳單硬上限**：輸入 token 在呼叫前沒有跨模型一致的計數器，當次回應可能已超出門檻。
- 缺少 usage 時仍可使用已驗證的當次結果，但不再追加模型呼叫，不把未知當作零。
- 整輪等待期限涵蓋模型步驟；逾時取消等待，外部供應商是否停止計費取決於其服務。
- 模型成本紀錄保留模型與 token；角色不沿用基底模型的單一價格，費率未設定時金額維持未知。

## 持久恢復與隱私

Flyway V3 新增 `quotation_model_checkpoint`，不修改既有表或資料。圖片觀察按 owner、圖片內容／ID、提示詞、Schema、端點／模型設定隔離；文字結果另包含草稿 revision 與訊息 ID。同事件回復仍扣除已保存步驟的配額，成功結果重新通過業務驗證；不同事件重用圖片不再支付模型呼叫。

快取保存七天，每小時清除過期內容。其中包含 OCR／報價文字，應與客戶資料一起管理備份與存取權限。Log 只記錄角色、呼叫數、token、快取狀態及 requestId，不記錄提示詞、結果或金鑰。

HTTP 回應到保存之間若程序崩潰，或外部呼叫失敗但供應商已計費，仍可能在事件重試時再付費；不宣稱跨供應商 exactly-once 計費。原有草稿事件冪等與正式報價確認防重機制維持不變。

## 回復舊流程

設定 `AI_WORKFLOW_ENABLED=false`，重新建立 App 容器即可回到舊單模型路徑。保留 V3 資料表，不需刪資料或執行 down -v。這是關閉功能；若要回退至不認識 V3 的舊映像，須另外處理 Flyway 版本相容性。

## 驗證

`QuotationModelWorkflowTest` 使用本機 HTTP stub 與真實 SQLite checkpoint，覆蓋三角色、五種格式的原驗證器、CSV 零呼叫、重啟恢復、跨 owner 隔離、TTL、模型變更、各種配額、獨立金鑰、拒絕、HTTP 失敗與逾時。原有完整回歸測試繼續執行。

Stub 能證明流程與契約，不能證明某個真實模型的辨識品質。正式啟用前用實際可用的模型與去識別化案例測量欄位正確率、補問率、每筆 token 與延遲；LINE 外網和公司資產啟用仍是原有部署前置條件。
