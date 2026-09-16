# 低 token 報價穩定性

狀態：2026-09-15 已實作並加入回歸測試；尚未完成真實模型重複抽樣與 LINE 端到端驗收。Document 不負責 AI 報價。

## 已實作

- **CSV 草稿入口**：LINE 私訊上傳 UTF-8 CSV，直接解析成草稿，不呼叫 AI 抽取。仍須預覽及確認。參考 [CSV 輸入契約](quotation-input-csv.md)。
- **嚴格結構化輸出**：自然語言請求使用 response_format 的 json_schema 與 strict 模式。傳輸 Schema 處理 required／nullable、共用品項 enum，伺服器保留來源證據、品項、數量及價格驗證。供應商必須支援此契約，不會靜默退回自由文字。
- **縮減內容**：CNS／GENERAL 只傳所選格式的啟用品項；MARINE／BLANK／SALES 與未選格式不傳固定目錄。API system prompt 不再重複內嵌 Schema。圖片保留在草稿，普通文字修正不重送所有圖片。
- **有限修復**：JSON／業務契約錯誤最多再呼叫一次，使用相同 Schema 與驗證；過大的原始回應不送回修復。截斷、拒絕與 HTTP 錯誤不走此修復迴圈。成本必須包含修復呼叫。
- **分類錯誤**：區分 AI_OUTPUT_TRUNCATED、AI_REFUSED、AI_RESPONSE_INVALID；記錄解析是否通過、嘗試次數與錯誤碼。HTTP 200 不等於有效報價。
- **輸出預算**：AI_MAX_COMPLETION_TOKENS 預設 4000，可設 256–32000；仍受模型限制。先觀察用量及截斷率，再調低預算。
- **S3 相容**：草稿引用圖片與簽名圖片預覽改用物件儲存讀取，修正 Docker 模式仍嘗試解析本機路徑的問題。

正式計價、草稿狀態、預覽、確認及產檔由程式處理。Structured Outputs 約束結構，不能保證內容正確。[OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)

## 實際使用

固定或重複報價優先使用 CSV，AI 抽取使用零模型 token。自然語言先提供格式、代碼與數量；缺少價格或數量時補值，不讓模型猜測。輸出截斷時增加預算或縮短輸入；PDF／LINE 失敗依既有快照重試，不重新抽取。

減少不必要內容與模型呼叫符合官方延遲最佳化方向；成本仍需計入圖片、推理及修復 token。[OpenAI Latency optimization](https://developers.openai.com/api/docs/guides/latency-optimization)

## 後續量測

固定模型、提示詞、Schema 與主檔版本，案例涵蓋五種格式、別名、缺值、衝突、多品項、OCR、追加／刪除及重送事件。每案至少重跑 5 次，記錄首次 Schema 通過率、品項／數量正確率、需補問率、截斷率、最終交付率、每成功報價總 token 與 p95 延遲。

目前測試驗證契約與程式行為，不能宣稱真實模型成功率提升百分比。2026-09-16 新增的 [多模型 workflow](quotation-model-workflow.md) 已提供分角色路由、圖片觀察持久快取、配額與步驟恢復，預設保留舊路徑。精確代碼／別名檢索與具體模型版本選擇仍應依量測決定。
