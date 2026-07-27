# Doc 3: 版本編號、Release 與 Push SOP

當前版本：`@assets-manager-linebot@0.1.0`

---

## 一、版本編號規則

### 1.1 格式

```
@本專案@版本號
```

實際套用時，「本專案」使用 `pom.xml` 的 `artifactId`：

```
@assets-manager-linebot@0.1.0
```

這個字串是**唯一的正式版本識別**，出現在：

| 位置 | 形式 |
|---|---|
| Git tag | `@assets-manager-linebot@0.1.0` |
| GitHub Release 標題 | `@assets-manager-linebot@0.1.0` |
| `pom.xml` `<version>` | `0.1.0`（Maven 不接受 `@`，只放數字部分） |
| 文件標頭 | `適用版本：@assets-manager-linebot@0.1.0` |

### 1.2 版本號採語意化版本

`主版本.次版本.修訂號`

| 位數 | 何時遞增 | 本專案的實例 |
|---|---|---|
| **主版本** | 不相容變更：既有指令語法改變、資料表結構破壞性異動、資產庫目錄結構改變 | 若把 `zd` 編號格式改掉，或改變實體資料夾層級 |
| **次版本** | 新增功能且向下相容 | 新增 `#報價` 指令、新增一種訊息型態的收錄 |
| **修訂號** | 修 bug、文件、重構，行為不變 | 修正中文編碼、補上遺漏的路徑檢查 |

### 1.3 版本規劃

| 版本 | 內容 | 狀態 |
|---|---|---|
| `0.1.0` | 資產收錄、zd 編號歸檔、查詢取用、AI 資料提取 | 目前 |
| `0.2.0` | 報價公式實作（待提供公式） | 規劃中 |
| `0.3.0` | PDF 報價單產出（待提供模板） | 規劃中 |
| `1.0.0` | 上線至公司伺服器並穩定運行 | 規劃中 |

> `0.x` 期間允許次版本帶不相容變更，但仍需在 Release Notes 明確標示。

### 1.4 需要同步修改的位置

升版本時，**以下位置必須一起改**，缺一個就會對不上：

1. `pom.xml` 的 `<version>`
2. `docs/reference/index.md` 標頭的「適用版本」
3. `docs/02-linebot-rules.md` 標頭的「適用版本」
4. 本檔案標頭的「當前版本」
5. 本檔案「版本規劃」表格的狀態欄

Dockerfile **不需要**改——產出檔名由 `pom.xml` 的 `<finalName>app</finalName>` 固定為 `app.jar`。

---

## 二、Push SOP

### 2.1 分支規則

| 分支 | 用途 | 可否直接 push |
|---|---|---|
| `main` | 穩定版本，隨時可部署 | ❌ 一律走 PR |
| `feature/*` | 新功能 | ✅ |
| `fix/*` | 修 bug | ✅ |
| `docs/*` | 純文件 | ✅ |

命名用小寫加連字號：`feature/quotation-formula`、`fix/chinese-folder-encoding`。

### 2.2 Push 前檢查清單

**每一項都要過，不可跳過。**

| # | 檢查 | 指令 |
|---|---|---|
| 1 | 測試全過 | `./mvnw test` |
| 2 | 打包成功 | `./mvnw clean package` |
| 3 | 沒有夾帶機密 | `git diff --cached --name-only` 確認沒有 `.env` |
| 4 | 沒有夾帶資料 | 確認沒有 `assets-store/`、`*.db` |
| 5 | 文件已同步 | 新增／刪除類別時，`docs/reference/` 對應頁與 `index.md` 都已更新 |
| 6 | Javadoc 完整 | 新類別有【職責】標頭，新方法有說明與 `@param`／`@return` |

第 5、6 項是本專案的硬性規定：**類別文件與程式碼在同一個 commit 內同步**。分開做的結果一定是文件永遠落後。

### 2.3 Commit 訊息格式

```
<類型>: <一句話說明>

<為什麼要這樣改，而不是改了什麼>
```

類型使用：`feat`、`fix`、`docs`、`refactor`、`test`、`chore`

範例：

```
fix: 執行階段映像改用非 Alpine 的 temurin

Alpine 的 musl 沒有 UTF-8 locale，JVM 的 sun.jnu.encoding 會退化成
ASCII，建立中文分類資料夾時全部變成問號。編譯階段不碰中文檔名，
可以繼續用 Alpine。
```

說明「為什麼」比「改了什麼」重要——「改了什麼」看 diff 就知道，「為什麼」只有當下的你知道。

### 2.4 Push 流程

```bash
git switch -c feature/your-change
```

```bash
./mvnw clean test
```

```bash
git add -A && git status
```

```bash
git commit
```

```bash
git push -u origin feature/your-change
```

開 PR 指向 `main`，描述需含：改了什麼、為什麼、如何驗證。

---

## 三、Release SOP

### 3.1 前置條件

- [ ] 要發布的內容都已合併進 `main`
- [ ] `main` 上 `./mvnw clean package` 通過
- [ ] 已在測試群組跑過 [驗收清單](02-linebot-rules.md#24-驗收清單)
- [ ] 版本號已依 [1.4 節](#14-需要同步修改的位置) 全部同步

### 3.2 步驟

**① 切到 main 並更新**

```bash
git switch main && git pull
```

**② 升版本號**

修改 `pom.xml` 與三份文件的版本標記（見 1.4 節）。

**③ 驗證**

```bash
./mvnw clean package
```

**④ 提交版本變更**

```bash
git commit -am "chore: 發布 @assets-manager-linebot@0.1.0"
```

**⑤ 打 tag**

tag 名稱使用完整格式：

```bash
git tag -a "@assets-manager-linebot@0.1.0" -m "@assets-manager-linebot@0.1.0"
```

**⑥ 推送**

```bash
git push origin main --follow-tags
```

**⑦ 建立 GitHub Release**

```bash
gh release create "@assets-manager-linebot@0.1.0" --title "@assets-manager-linebot@0.1.0" --notes-file RELEASE_NOTES.md
```

### 3.3 Release Notes 格式

```markdown
## @assets-manager-linebot@0.1.0

### 新增
- 群組圖片自動收錄，落地至本機磁碟
- 引用回覆輸入 `zd` 編號即自動建立資料夾並歸檔
- `#查`／`#標籤`／`#說明` 指令
- AI 規格資料提取（`#報價`）

### 修正
- 執行階段映像改用非 Alpine，修正中文資料夾變成問號

### 已知限制
- 報價公式尚未定義，`#報價` 只會回傳 AI 讀出的欄位
- PDF 報價單模板尚未提供

### 升級注意
- 新增 `AI_*` 四個環境變數，請參考 `.env.example` 更新 `.env`
```

「已知限制」與「升級注意」兩節不可省略。前者讓使用者不會把未完成當成 bug 回報，後者避免部署後才發現少設環境變數。

### 3.4 部署到公司伺服器

```bash
git fetch --tags && git checkout "@assets-manager-linebot@0.1.0"
```

```bash
docker compose up --build -d
```

```bash
docker compose ps
```

確認 `STATUS` 為 `Up (healthy)`，再依 [驗收清單](02-linebot-rules.md#24-驗收清單) 於正式群組確認。

> **部署一律 checkout tag，不要用 `main`。** 用 `main` 部署時，沒有人能確定伺服器上跑的到底是哪個版本。

### 3.5 回滾

```bash
git checkout "@assets-manager-linebot@0.0.9"
```

```bash
docker compose up --build -d
```

**資料不會回滾**——`ASSETS_ROOT` 底下的圖片與 `assets.db` 是營運資料，與程式版本無關。若該版本曾異動資料表結構，回滾前必須確認舊版程式能讀新版結構，否則要先還原資料備份。

---

## 相關文件

- [部署與外部串接指南](01-bot-deployment.md)
- [LINE Bot 規則與各階段處理](02-linebot-rules.md)
- [類別索引](reference/index.md)
