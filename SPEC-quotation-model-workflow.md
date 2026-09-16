# 小型多模型報價 workflow

使用者授權：2026-09-16 完成開發、測試並保留舊版邏輯。

狀態：已實作；386 項完整測試通過，Docker 開關兩條路徑與 PostgreSQL V3 驗證通過。真實模型品質需以部署者的模型與案例另行評估。

## 不變條件

- AI_WORKFLOW_ENABLED 預設 false，既有單模型、CSV、草稿合併、計價、確認與產檔保持原行為。
- 開啟後以程式決定 TEXT、VISION、ESCALATION 角色；不同角色可使用不同 OpenAI-compatible Chat Completions 端點，不假設其他原生 API 相容。
- 圖片先轉成受 Schema 約束的觀察資料，再由文字模型抽取既有 patch；圖片觀察是資料，不是操作指令。
- 缺少原始資料沿用補問；只有契約錯誤或候選 CONFLICT 才升級一次，拒絕／認證／HTTP 失敗不轉模型規避。
- 每輪有呼叫次數、累計輸出 token 配額、實際 total token 門檻與等待期限；供應商未回 usage 時不允許追加呼叫。
- 不變的成功步驟與圖片觀察按使用者、草稿／事件或圖片內容雜湊、角色設定、提示詞／Schema 隔離，保存於有到期時間的資料庫快取。回復時重用已保存結果但重新業務驗證。
- 外部 API 不具跨 DB 交易；程序在收到回應到保存之間崩潰仍可能重呼叫，不宣稱 exactly-once 模型計費。
- Docker 可掛載角色設定檔與獨立 Secret；變更端點不能默默沿用基底金鑰。

## 驗證

本機 HTTP stub 驗證角色／金鑰隔離、Schema、OCR 重用、一次升級、預算停止與無 usage 行為；SQLite 測試持久快取並用 PostgreSQL Docker 檢查新 migration。完整 Maven 回歸與 Docker readiness 必須通過。真實供應商品質與帳號可用性另列，不能以 stub 宣稱真實成功率。
