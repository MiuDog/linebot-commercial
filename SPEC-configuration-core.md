# Spec：configuration-core

## Objective

建立 Windows App 共用的設定核心，讓非技術使用者可在首次安裝完成後或日後編輯設定，不必手動建立 `.env`，並避免 API Key、Token 等機密資料以明文保存或出現在 Log。

主要流程：

1. 首次啟動若找不到有效設定，顯示設定精靈，不啟動 Spring Boot。
2. 使用者完成必要欄位並通過驗證後，以原子操作保存設定。
3. 非機密設定保存為使用者層級的 properties；機密設定透過 Windows DPAPI 綁定目前使用者加密。
4. Desktop bootstrap 解密並映射成 Spring Boot default properties，再建立 ApplicationContext。
5. 執行期間編輯設定後，依序停止 ngrok 與 Spring context，再以新設定重新啟動。

設定位置：

- 設定根目錄：`%LOCALAPPDATA%\AssetsManagerLinebot\config`
- 一般設定：`application.properties`
- 加密機密：`secrets.dat`
- 預設資料根目錄：`%LOCALAPPDATA%\AssetsManagerLinebot\data`
- 不在安裝目錄建立或讀取正式環境的 `.env`。

欄位分組：

- 必要：LINE Channel Token、LINE Channel Secret、資料根目錄。
- AI：API URL、API Key、Model、必要欄位、Timeout、價格設定。
- 語音：啟用狀態、MCP URL、MCP Token、相關 Model。
- 報價：簽章 Secret、圖片連結 Secret、輸出與工作排程設定。
- Log：Level、單檔大小、保留天數、總容量及資源記錄週期。
- ngrok：是否啟用、agent 路徑、Authtoken；公開網址由 `ngrok-connector` 回填。

以下欄位視為機密，畫面只可顯示遮罩值：

- `LINE_BOT_CHANNEL_TOKEN`
- `LINE_BOT_CHANNEL_SECRET`
- `AI_API_KEY`
- `VOICE_MCP_AUTH_TOKEN`
- `ASSETS_SYNC_TOKEN`
- `QUOTATION_POSTBACK_SECRET`
- `QUOTATION_IMAGE_LINK_SECRET`
- `NGROK_AUTHTOKEN`

## Tech Stack

- Java 25
- Spring Boot 4.1.0
- Java Swing 表單元件
- Windows DPAPI，透過經審核且鎖定版本的 JNA Platform bridge 存取
- Java `Properties` 作為一般設定格式
- JUnit 6、Spring Boot Test

## Commands

```powershell
# 執行設定核心測試
.\mvnw.cmd -Dtest="*Configuration*Test,*Secret*Test" test

# 執行完整驗證
.\mvnw.cmd clean verify

# 建立可執行 JAR
.\mvnw.cmd -DskipTests package
```

## Project Structure

```text
src/main/java/dev/myudog/assetsmanagerlinebot/desktop/config/
  AppConfiguration.java
  AppConfigurationLoader.java
  AppConfigurationValidator.java
  ConfigurationWizard.java
  DpapiSecretStore.java
src/test/java/dev/myudog/assetsmanagerlinebot/desktop/config/
  對應單元與整合測試
```

`AppConfiguration` 是提供給其他模組的唯一設定契約。其他模組不得直接解析設定檔或解密 `secrets.dat`。

## Code Style

所有新增程式套用 `personal-code-style`，包含方法中文說明、Log 上方中文註解、外部函式呼叫目的註解，以及既定的換行與 Tab 規則。

```java
/**
 * 驗證設定並以原子操作保存。
 */
public void save(
	AppConfiguration configuration
) {
	var violations = validator.validate(configuration);
	if (!violations.isEmpty()) throw new InvalidConfigurationException(violations);

	// 呼叫 Windows DPAPI，讓機密值只能由目前使用者解密。
	secretStore.save(configuration.secrets());

	// 記錄設定版本，不輸出任何機密欄位。
	logger.info("應用程式設定已更新，schemaVersion={}", configuration.schemaVersion());
}
```

## Testing Strategy

- 單元測試驗證必填欄位、URL、Port、檔案路徑、數值範圍及欄位分類。
- 以替代 SecretStore 測試加密前後的資料邊界，不在 CI 依賴真實 Windows 使用者憑證。
- Windows 整合測試驗證 DPAPI round trip，且不同使用者或損毀密文無法解密。
- 測試原子寫入：寫入失敗時保留上一份有效設定。
- 測試 Log 與例外訊息不含原始 Token、API Key 或完整密文。
- 測試舊設定缺少新欄位時套用預設值，未知欄位保持向前相容。

## Boundaries

- Always：先驗證再保存；機密欄位加密；以暫存檔加原子替換保存；Log 經敏感資料清理。
- Ask first：新增或更換 DPAPI bridge 依賴；改變設定位置；新增需保存的個人資料；變更設定 schema 的不相容語意。
- Never：寫入 Windows 全域環境變數；提交真實 `.env`；以明文保存機密；在 UI 或 Log 回顯完整 Token。

## Success Criteria

- 全新 Windows 使用者首次啟動必定進入設定精靈，取消後不會在缺少設定時啟動後端。
- 有效設定保存後，重新啟動 App 可取得相同的一般設定與解密後機密。
- `application.properties` 不含任何被分類為機密的值。
- 不合法欄位會在對應控制項旁顯示中文錯誤，且不覆蓋現有設定。
- 設定變更可觸發受控重新啟動，失敗時保留上一份可用設定並顯示原因。
- 自動化測試掃描 Log、測試輸出與設定檔，找不到測試用秘密原文。

## Open Questions

- 正式產品名稱、公司／發行者名稱及支援網址尚待發佈前提供，不阻擋核心開發。
- JNA Platform 的精確版本與授權清單在實作計畫階段鎖定；新增依賴須通過測試與弱點檢查。

