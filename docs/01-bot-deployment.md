# Doc 1: LINE Bot 前端服務部署與外部串接指南

本文件詳細說明如何在全新電腦上，將 `assets-manager-linebot` 透過 Docker 部署，並與獨立運行的 `cloudstorage` 服務在內網中完成綁定。

## 1. 啟動前的依賴檢查

本專案採用方案 B（完全去耦合）架構。在啟動本服務前，請確保以下兩點已完成：
1. 全域共享網路 `my-shared-network` 已經建立。
2. 隔壁的 `cloud-storage` 服務已經在運行中，且其容器名稱為 `cloudstorage-service`。

*(若尚未完成上述準備，請先至 `cloud-storage` 專案目錄下參閱其說明文件。)*

## 2. LINE Webhook 外部公網串接 (Ngrok)

由於 LINE 伺服器必須透過 HTTPS 公網 URL 將訊息推送到你的本機容器（8088 Port），在全新環境下，建議使用 Ngrok 進行對外盲穿：

1. 前往 [Ngrok 官網](https://ngrok.com/) 下載對應作業系統的執行檔。
2. 在終端機執行以下指令開啟隧道（對應本容器的 8088 Port）：
   ```bash
   ngrok http 8088

```

3. 複製 Ngrok 生成的 `https://xxxx.ngrok-free.app` 網址。
4. 回到 LINE Developers 後台，將 Webhook URL 修改為：
`https://xxxx.ngrok-free.app/callback` 并點擊 Verify 驗證。

## 3. 內網通訊驗證

當本容器啟動後，它會自動將收到的檔案透過 `http://cloudstorage-service:8090/api/storage/upload` 串流轉發。你可以透過查看 Bot 容器的 Log 來確認通訊是否正常：

```bash
docker logs -f assets-manager-linebot-service

```