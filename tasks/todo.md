# LINE AI 自動化報價系統工作項目

依據：[正式 SRS](../docs/08-quotation-automation-srs.md)
執行原則：每項任務先 RED 測試、再最小 GREEN 實作、最後重構；每個任務完成後保持可建置。

## 目前進度

| 任務 | 狀態 | 證據 |
| --- | --- | --- |
| Task 1–16 | ✅ 實作與自動測試完成 | 完整 Maven 309/309 與打包通過 |
| Task 17 | ✅ 完成 | [AC-01 至 AC-22 證據矩陣](../docs/10-acceptance-verification-matrix.md) 已定稿；未取得的外部／人工證據明確維持部分通過 |
| Task 18 | ✅ 實作與自動測試完成 | observability、敏感資料與 AI 成本測試；人工成功／失敗日誌演練仍待保存 |
| Task 19–28 | ✅ 實作與自動測試完成 | 桌面設定、DPAPI、生命週期、IPC、Swing、Log 與 ngrok 契約測試通過；真實 UI／Token smoke 待外部驗收 |
| Task 29–31 | ✅ 本機封裝與生命週期完成 | 自帶 Java app image 與單一 Setup 已建立；install／repair／保留／purge 證據完成，乾淨 VM 待驗收 |
| Task 32–34 | 🟡 本機實作完成 | CI、Release、簽章、SBOM、notices 與 runbook 已建立；GitHub、正式憑證與核准 EULA／品牌待外部設定 |

下列核取方塊保留原始逐項驗收用途；總體實作狀態以上表為準，實機或外部服務證據不足者維持未勾選。

## Task 1：遷移報價資料模型

**Description：** 將現有草稿、請求、正式報價與明細表升級為 SRS 狀態、空白數量、動態品項、確認、檔案、流水號、下載與 LINE 傳送可用的資料模型，並保留既有資料。

**Acceptance criteria：**

- [ ] Schema 可由空資料庫及目前版本資料庫重複初始化。
- [ ] 空白數量、動態品項、狀態、日期流水號、檔案與傳送紀錄皆有約束及索引。
- [ ] 舊資產、CNS／一般架主檔及既有報價資料不被刪除。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationSchemaTest test`
- [ ] 真實舊版 SQLite 複本升級測試通過。

**Dependencies：** None
**Files likely touched：** `schema.sql`、`QuotationSchemaTest.java`、新增 migration 測試資源
**Estimated scope：** Medium

## Task 19：建立桌面設定模型與驗證契約

**Description：** 建立桌面模式可使用的設定模型、欄位分類、預設路徑及輸入驗證，並保持既有 server／Docker 環境變數模式不變。

**Acceptance criteria：**

- [x] 一般欄位與機密欄位有單一分類來源，必要欄位、URL、Port、數值及路徑驗證可定位至欄位。
- [x] 預設設定與資料路徑位於目前使用者的 `%LOCALAPPDATA%\AssetsManagerLinebot`。
- [x] desktop mode 未啟用時仍沿用既有 Spring Boot 啟動行為。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=AppConfigurationTest,AppConfigurationValidatorTest test`
- [x] Style gate：`.\mvnw.cmd -Dtest=JavaPersonalStyleTest,ChineseCommentCoverageTest test`
- [x] Regression：`.\mvnw.cmd -Dtest=AssetsManagerLinebotApplicationTests test`

**Dependencies：** Task 18
**Files likely touched：** `AppConfiguration.java`、`AppConfigurationField.java`、`AppConfigurationValidator.java`、對應測試
**Estimated scope：** Medium

## Task 20：建立 Windows DPAPI 機密儲存

**Description：** 以隔離介面與鎖定版本的 JNA Platform 存取 Windows DPAPI，讓 Token 與 API Key 只可由目前 Windows 使用者解密。

**Acceptance criteria：**

- [x] 機密值 round trip 成功，損毀密文會安全失敗；密文綁定目前 Windows 使用者的驗證由 DPAPI 提供。
- [x] 非 Windows 測試可使用 in-memory provider，不載入 Windows native API。
- [x] 例外與 Log 不含秘密原文或完整密文。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=DpapiSecretStoreTest test`
- [x] Dependency：`.\mvnw.cmd dependency:tree -Dincludes=net.java.dev.jna:*`

**Dependencies：** Task 19
**Files likely touched：** `pom.xml`、`SecretStore.java`、`DpapiSecretStore.java`、對應測試
**Estimated scope：** Medium

## Task 21：建立原子設定保存與 Spring 映射

**Description：** 保存一般 properties 與 DPAPI 密文，原子替換有效設定，並在 Spring 啟動前轉換成 default properties。

**Acceptance criteria：**

- [x] 保存失敗時上一份設定仍可讀取，未知欄位可向前相容。
- [x] 一般設定檔不包含 Task 19 分類的機密值。
- [x] Spring properties 映射覆蓋現有 `.env.example` 所需桌面欄位。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=AppConfigurationRepositoryTest,DesktopSpringPropertiesTest test`
- [x] Secret scan：測試設定目錄找不到測試秘密原文。

**Dependencies：** Task 20
**Files likely touched：** `AppConfigurationRepository.java`、`DesktopSpringProperties.java`、對應測試
**Estimated scope：** Medium

## Task 22：建立首次設定與編輯設定精靈

**Description：** 建立繁體中文 Swing 設定精靈，依 LINE、AI、語音、報價、Log 與 ngrok 分頁編輯設定。

**Acceptance criteria：**

- [x] 首次設定缺少必要欄位時不可保存，錯誤顯示於對應欄位。
- [x] 機密欄位使用密碼控制項，既有值只顯示遮罩，留空不覆蓋。
- [x] 取消首次設定不啟動 Spring，編輯保存後回傳需要重啟的結果。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=ConfigurationWizardModelTest,ConfigurationWizardTest test`
- [ ] Windows 人工 smoke：鍵盤操作、錯誤焦點與遮罩顯示。

**Dependencies：** Task 21
**Files likely touched：** `ConfigurationWizard.java`、`ConfigurationWizardModel.java`、`ConfigurationWizardResult.java`、對應測試
**Estimated scope：** Medium

## Checkpoint W1：設定核心

- [x] Task 19–22 測試通過。
- [x] Windows DPAPI round trip 與敏感資料掃描通過。
- [x] 現有 server／Docker 啟動與完整測試無 regression。

## Task 23：建立 Desktop bootstrap 與 Spring 生命週期

**Description：** 在既有 Spring Boot 入口前加入 desktop bootstrap，依模式載入設定、啟動與停止 ApplicationContext，並保留 server mode。

**Acceptance criteria：**

- [x] packaged desktop mode 先完成設定再啟動 Spring，server mode 保持原行為。
- [x] 狀態依 `STARTING → RUNNING → STOPPING → STOPPED` 或 `FAILED` 合法轉換。
- [x] 設定變更採受控程序重啟，釋放 Spring、SQLite、Port 與 Log 資源。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=DesktopLifecycleCoordinatorTest,DesktopApplicationTest test`
- [x] Regression：`.\mvnw.cmd test`

**Dependencies：** Checkpoint W1
**Files likely touched：** `AssetsManagerLinebotApplication.java`、`DesktopApplication.java`、`DesktopLifecycleCoordinator.java`、對應測試
**Estimated scope：** Medium

## Task 24：建立安全的單一執行個體 IPC

**Description：** 以 FileLock、loopback socket 與每次啟動 nonce 確保同一使用者只有一個後端，第二次執行只傳遞顯示或設定命令。

**Acceptance criteria：**

- [x] 第二個執行個體不建立 Spring context，能要求第一個顯示視窗或設定頁。
- [x] 錯誤 nonce、非預期命令及 malformed payload 被拒絕。
- [x] 程序結束後 metadata、socket 與 lock 都可再次取得。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=SingleInstanceCoordinatorTest,DesktopIpcServerTest test`

**Dependencies：** Task 23
**Files likely touched：** `SingleInstanceCoordinator.java`、`DesktopIpcServer.java`、`DesktopIpcCommand.java`、對應測試
**Estimated scope：** Medium

## Task 25：建立狀態視窗與系統匣

**Description：** 建立繁體中文主視窗及 SystemTray，顯示服務狀態、網址與操作，關閉視窗只隱藏而不中止後端。

**Acceptance criteria：**

- [x] 視窗與系統匣提供顯示、設定、重新啟動及結束操作。
- [x] 系統匣不可用時不允許形成無法再次顯示的背景程序。
- [x] 所有 Swing 元件只在 EDT 建立與更新。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=DesktopWindowModelTest,DesktopTrayControllerTest test`
- [ ] Windows 人工 smoke：關閉、系統匣恢復、再次開啟及明確結束。

**Dependencies：** Task 24
**Files likely touched：** `DesktopWindow.java`、`DesktopWindowModel.java`、`DesktopTrayController.java`、對應測試
**Estimated scope：** Medium

## Task 26：建立即時 JSON Log 檢視

**Description：** 追蹤既有 rolling JSON Log，提供固定容量 buffer、等級篩選、搜尋、暫停捲動及開啟資料夾。

**Acceptance criteria：**

- [x] rotation 後持續讀取新檔，Log buffer 不會無限成長。
- [x] UI 可依等級與文字過濾，讀檔失敗顯示可操作狀態。
- [x] Log 顯示前再次通過敏感資料清理。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=LogTailServiceTest,DesktopLogBufferTest test`
- [ ] 誘發一筆錯誤，僅靠視窗 Log 可定位事件與原因。

**Dependencies：** Task 25、Task 18
**Files likely touched：** `LogTailService.java`、`DesktopLogBuffer.java`、`DesktopWindow.java`、對應測試
**Estimated scope：** Medium

## Checkpoint W2：桌面背景執行

- [x] Task 23–26 測試通過。
- [ ] Windows 上第二次開啟只顯示既有視窗，仍只有一個 Spring／SQLite 程序。
- [ ] 關閉視窗後 webhook 持續，明確結束後所有資源釋放。

## Task 27：建立 ngrok process 與 local API 契約

**Description：** 以可替代的 process 與 local API adapter 建立 ngrok 啟動、狀態、URL 解析及停止流程。

**Acceptance criteria：**

- [x] agent 路徑只接受存在的 executable，不允許附加命令片段。
- [x] Authtoken 只進入 child environment，不進入命令列、例外或 Log。
- [x] timeout、錯誤 JSON、空 tunnel 及 child process 結束都有穩定狀態。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=NgrokProcessTest,NgrokLocalApiClientTest test`

**Dependencies：** Checkpoint W1
**Files likely touched：** `NgrokProcess.java`、`NgrokLocalApiClient.java`、`NgrokStatus.java`、對應測試
**Estimated scope：** Medium

## Task 28：整合 ngrok 與 Desktop／Spring 啟動

**Description：** 依設定選擇 ngrok 或本機模式，在 Spring 前取得公開 URL，並把狀態與 callback URL顯示於主視窗。

**Acceptance criteria：**

- [x] 未啟用時不搜尋或啟動 ngrok，啟用時將 HTTPS URL 注入 `PUBLIC_BASE_URL`。
- [x] 失敗時可重試、開啟設定或本機啟動，不使用過期 URL。
- [x] 結束時只停止本 App 建立的 ngrok ProcessHandle。

**Verification：**

- [x] RED/GREEN：`.\mvnw.cmd -Dtest=NgrokConnectorTest,DesktopNgrokIntegrationTest test`
- [ ] 專用測試 Token 人工 smoke：公開 callback 可回到本機服務。

**Dependencies：** Task 23、Task 25、Task 27
**Files likely touched：** `NgrokConnector.java`、`DesktopLifecycleCoordinator.java`、`DesktopWindowModel.java`、對應測試
**Estimated scope：** Medium

## Checkpoint W3：可選 ngrok

- [x] Task 27–28 測試通過。
- [ ] ngrok 成功、失敗、本機模式與重啟路徑可重現。
- [x] Log 與程序資訊找不到測試 Authtoken。

## Task 29：建立可重現的 jpackage App image

**Description：** 建立 PowerShell 封裝腳本，以 Maven JAR 與 JDK 25 產生包含 Runtime 的 Windows app image。

**Acceptance criteria：**

- [x] script 驗證版本、輸入、輸出及外部命令 exit code。
- [ ] app image 在未安裝系統 JRE 的 Windows VM 可啟動 desktop mode。
- [x] server／Docker 產物與 desktop launcher 選項清楚分離。

**Verification：**

- [x] `.\mvnw.cmd clean verify package`
- [x] `powershell.exe -NoProfile -File scripts\package-windows-app.ps1 -Version 0.1.0`
- [x] `powershell.exe -NoProfile -File scripts\test-windows-app-image.ps1 -AppImagePath dist\app-image\AssetsManagerLinebot`

**Dependencies：** Checkpoint W2、Checkpoint W3
**Files likely touched：** `pom.xml`、`scripts/package-windows-app.ps1`、`packaging/windows/launcher.properties`、測試 script
**Estimated scope：** Medium

## Task 30：建立 NSIS 初次安裝與維護模式

**Description：** 把 app image 封裝成單一 per-user Setup.exe，支援首次安裝、編輯設定、修復／升級及移除。

**Acceptance criteria：**

- [ ] 首次安裝建立開始功能表與選用桌面捷徑，完成後開啟設定精靈。
- [ ] 已安裝狀態提供編輯、修復／升級及移除，升級保留設定與資料。
- [ ] 缺少 Excel／印表機時警告但不阻擋安裝。

**Verification：**

- [x] `powershell.exe -NoProfile -File scripts\build-windows-installer.ps1 -Version 0.1.0`
- [ ] Windows VM 人工執行首次與維護模式。

**Dependencies：** Task 29
**Files likely touched：** `packaging/windows/installer.nsi`、`scripts/build-windows-installer.ps1`、安裝資源與 license
**Estimated scope：** Medium

## Task 31：建立安全升級、移除與 Installer 驗收

**Description：** 建立安裝器 smoke script 與清除邊界，驗證停止既有 App、版本升級、預設保留資料及選擇性完整清除。

**Acceptance criteria：**

- [x] 升級前正常停止 App，失敗時不留下半更新產品。
- [x] 預設解除安裝保留資料；purge 只刪除解析後的產品專屬路徑。
- [ ] 安裝、啟動、重開、編輯、修復、升級與移除都有可定位證據。

**Verification：**

- [x] `powershell.exe -NoProfile -File scripts\test-windows-installer.ps1 -InstallerPath dist\AssetsManagerLinebot-Setup-0.1.0.exe`

**Dependencies：** Task 30
**Files likely touched：** `scripts/test-windows-installer.ps1`、`installer.nsi`、驗收文件與測試 fixtures
**Estimated scope：** Medium

## Checkpoint W4：單一 Setup.exe

- [ ] Task 29–31 驗收通過。
- [x] GitHub Release 預定資產只有一份 Setup.exe，且使用者不需另裝 Java。
- [ ] 預設保留與明確 purge 行為均在乾淨 Windows VM 驗證。

## Task 32：建立 Pull Request 與 main CI

**Description：** 建立最小權限 GitHub Actions，於 Ubuntu 與 Windows 使用 JDK 25／Maven Wrapper 執行完整驗證。

**Acceptance criteria：**

- [x] PR 與 main push 自動測試，Windows 執行 DPAPI／封裝相容檢查。
- [x] 第三方 Action 鎖定 commit SHA，job 預設只有 `contents: read`。
- [x] PR 無法取得簽章秘密或 Release 寫入權限。

**Verification：**

- [ ] GitHub workflow schema／action lint 通過；本機 YAML parse 與 SHA／權限 policy 檢查已通過。
- [ ] GitHub Pull Request 實際 workflow 通過。

**Dependencies：** Checkpoint W1
**Files likely touched：** `.github/workflows/ci.yml`、CI 驗證 script、README badge
**Estimated scope：** Medium

## Task 33：建立版本、簽章與 GitHub Release pipeline

**Description：** 建立 Tag 版本一致性、provider-neutral Authenticode 簽章與單一 Setup.exe GitHub Release。

**Acceptance criteria：**

- [x] Tag、Maven、launcher 與 Setup 版本不一致時立即失敗。
- [x] 正式 job 缺少或驗證不通過的簽章時不得建立公開 Release。
- [x] Release 只有一份 Setup.exe，SHA-256 寫入 Release Notes。

**Verification：**

- [ ] Dry run 產生未公開 artifact。
- [ ] 測試 Tag 完成簽章驗證、Release 建立與失敗清理演練。

**Dependencies：** Checkpoint W4、Task 32
**Files likely touched：** `.github/workflows/release-windows.yml`、`scripts/sign-windows-artifacts.ps1`、`scripts/verify-release.ps1`、release config
**Estimated scope：** Medium

## Task 34：建立商用授權、SBOM 與 Release runbook

**Description：** 產生第三方 notices、SBOM、商用發佈清單、憑證輪替、撤回與回復文件。

**Acceptance criteria：**

- [ ] App 內包含 EULA、第三方 notices 與可追溯 SBOM。
- [x] runbook 說明簽章秘密、重跑、撤回、上一版回復及 ngrok 授權邊界。
- [x] 正式品牌與法律欄位缺少時 Release gate 失敗而非使用 placeholder。

**Verification：**

- [x] SBOM／license 產生與內容檢查通過。
- [ ] 維護者依 runbook 完成一次 dry run。

**Dependencies：** Task 33
**Files likely touched：** `docs/release-runbook.md`、`docs/third-party-notices.md`、SBOM build config、EULA
**Estimated scope：** Medium

## Checkpoint W5：CI/CD 與 Release gate

- [ ] Task 32–34 驗收通過。
- [ ] 測試 Tag 產出可安裝 artifact，正式 Tag 必須簽章後才公開。
- [ ] 權限、secret、SBOM、checksum、撤回與回復證據完整。

## Task 35：完成 Windows 商用端對端驗收

**Description：** 逐項執行所有 Windows App 規格 Success Criteria，涵蓋乾淨 VM、真實外部服務與既有報價流程。

**Acceptance criteria：**

- [ ] Windows 10/11 x64 在無系統 Java 環境完成安裝、設定、背景執行、Log、維護與移除。
- [ ] 專用 LINE／AI／ngrok 憑證完成 webhook 全流程，Log 可追蹤且無機密洩漏。
- [ ] 有 Excel／印表機時完成正式 PDF；缺少時警告與降級行為正確。

**Verification：**

- [x] `.\mvnw.cmd clean verify`
- [ ] Windows installer 驗收矩陣、真實整合證據與 Release checklist 全數通過。

**Dependencies：** Checkpoint W5、Task 17、Task 18
**Files likely touched：** `docs/10-acceptance-verification-matrix.md`、Windows App 驗收文件、README、證據輸出
**Estimated scope：** Medium

## Checkpoint W6：正式商用 Release

- [ ] Task 19–35 與所有 W1–W5 Checkpoint 完成。
- [ ] 所有已核准規格 Success Criteria 具有直接證據。
- [ ] 已簽章 Setup.exe 可由 GitHub Release 安裝、維護與移除，且有可執行的回復方案。

## Task 2：升級 AI／OCR 2.x 契約

**Description：** 定義欄位 patch、標準品項、臨時／動態品項、刪除項目、缺漏清單、圖片評分與白名單下一動作，並拒絕 AI 金額計算及額外欄位。

**Acceptance criteria：**

- [ ] 基礎欄位及品項缺漏可分組表示並保留來源、原文及信心。
- [ ] 標準品項不得帶固定資料或計算金額；動態品項只能帶使用者明確提供的值。
- [ ] 非法 nextAction、額外欄位及非最高分選圖被拒絕。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationContractTest,QuotationAiPromptServiceTest,QuotationRequestValidationServiceTest test`

**Dependencies：** Task 1
**Files likely touched：** `quotation-request.schema.json`、`QuotationAiPromptService.java`、`QuotationRequestValidationService.java`、對應測試
**Estimated scope：** Medium

## Task 3：建立確定性報價列與計價

**Description：** 以純程式建立五格式報價列模型，支援 CNS／一般架全品項鎖價、刪除、兩筆臨時品項、空白／銷售動態品項及船用彙總。

**Acceptance criteria：**

- [ ] 未提及標準品項數量為 null，固定資料保留且不加入合計。
- [ ] 複價、未稅、5% 稅額及含稅總額以 BigDecimal 計算至兩位小數。
- [ ] 第三筆 CNS／一般架臨時品項被拒絕，AI 提供的計算結果不被採用。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationCalculationServiceTest test`

**Dependencies：** Task 1、Task 2
**Files likely touched：** 新增 `QuotationCalculationService.java`、`QuotationAdminRepository.java`、新增領域 record、對應測試
**Estimated scope：** Medium

## Task 4：實作草稿狀態機

**Description：** 建立可恢復的多輪草稿、patch 合併、缺漏決策、圖片決策、正式確認及取消狀態轉換。

**Acceptance criteria：**

- [ ] 同一使用者只有一張進行中草稿，新訊息只合併本次明確資料。
- [ ] 基礎與品項缺漏分組，船用／空白未決定圖片時不能確認。
- [ ] 確認與取消 postback 冪等，終態不可由舊事件復活。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationConversationServiceTest test`

**Dependencies：** Task 1、Task 2、Task 3
**Files likely touched：** 新增 `QuotationConversationService.java`、新增 `QuotationDraftRepository.java`、新增狀態模型、對應測試
**Estimated scope：** Medium

## Task 5：建立 LINE 報價訊息建構器

**Description：** 產生基礎缺漏、品項缺漏、圖片詢問、完整預覽、取消與確認的 LINE 訊息及簽名 postback。

**Acceptance criteria：**

- [ ] 缺漏以一則清單呈現，每個等待狀態都有取消按鈕。
- [ ] 完整預覽含動態標示、選圖及程式計算總額。
- [ ] postback 綁定草稿、使用者、版本及動作，竄改會被拒絕。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationLineMessageBuilderTest,QuotationPostbackSignerTest test`

**Dependencies：** Task 4
**Files likely touched：** 新增 `QuotationLineMessageService.java`、`LineStorageService.java`、新增 postback signer、對應測試
**Estimated scope：** Medium

## Task 6：接入私訊 webhook 與 postback

**Description：** 將 LINE 文字、圖片及 postback 事件路由到報價草稿，限制一對一來源並對事件做冪等處理。

**Acceptance criteria：**

- [ ] `source.type=user` 可建立／補充報價，群組只收到轉至私訊提示。
- [ ] postback 可取消、確認、拒絕圖片或要求修改。
- [ ] webhook 重送不重複合併、產出或回覆。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=LineWebhookControllerTest,QuotationLineWorkflowServiceTest test`

**Dependencies：** Task 4、Task 5
**Files likely touched：** `LineWebhookController.java`、`CommandService.java`、新增路由服務、對應測試
**Estimated scope：** Medium

## Task 7：接入名片 OCR 與候選圖片預覽

**Description：** 將草稿圖片送入 AI 視覺解析，區分名片欄位及工程候選圖，保存全部候選評分並回傳最高品質圖片預覽。

**Acceptance criteria：**

- [ ] 名片低信心欄位列為缺漏，不直接成為已確認資料。
- [ ] 全部候選原圖留在 `.pending`，最多一張被選取並回傳 HTTPS 預覽。
- [ ] 使用者可更換、移除；船用／空白移除後回到圖片決策狀態。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=SqliteQuotationDraftWorkflowPortTest,QuotationAiParsingServiceTest,QuotationPendingImagePreviewServiceTest test`

**Dependencies：** Task 2、Task 4、Task 6
**Files likely touched：** 新增 `QuotationImageService.java`、`QuotationAiParsingService.java`、`ImageArchiveService.java`、對應測試
**Estimated scope：** Medium

## Task 8：確認時分配流水號與建立快照

**Description：** 在最終確認時驗證必要資料，以交易分配 Asia/Taipei 每日流水號、建立正式快照並設定報價日及 15 天有效期限。

**Acceptance criteria：**

- [ ] 公司及工作名稱缺少時不能確認，取消草稿不耗號。
- [ ] 並行確認取得不同 `YYYYMMDD-XX`，第 100 張自然擴充。
- [ ] 正式快照不受後續主檔修改影響，銷售顯示 S 前綴。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationConfirmationServiceTest,QuotationConfirmationConcurrencyTest test`
- [ ] SQLite 並行整合測試通過。

**Dependencies：** Task 1、Task 3、Task 4
**Files likely touched：** 新增 `QuotationConfirmationService.java`、新增 `QuotationRepository.java`、對應測試
**Estimated scope：** Medium

## Task 9：建立報價資料夾並正式歸檔圖片

**Description：** 確認後建立 `報價單/YYYYMMDD-XX/`，將全部候選原圖由 `.pending` 搬入並原子更新資產相對路徑及報價關聯。

**Acceptance criteria：**

- [ ] 未確認／取消草稿不建立資料夾。
- [ ] 正式資料夾包含全部候選原圖且每張只有一份實體檔案。
- [ ] 搬移或 DB 更新失敗時可補償，資產同步不出現失聯路徑。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationAssetArchiveServiceTest,MediaControllerQuotationAssetTest test`

**Dependencies：** Task 7、Task 8
**Files likely touched：** `QuotationOutputDirectoryService.java`、新增 `QuotationStorageService.java`、`ImageArchiveService.java`、`AssetRepository.java`、對應測試
**Estimated scope：** Medium

## Task 10：輸出完整鎖價列與動態 Excel

**Description：** 改造 Excel 產生器使用正式快照，套用新檔名，支援空白數量、明確刪除、臨時／動態品項及船用彙總。

**Acceptance criteria：**

- [ ] CNS／一般架輸出全部未刪除品項；空白數量與複價儲存格真正為空。
- [ ] 空白／銷售可輸出動態品項，船用不顯示內部計算過程。
- [ ] Excel 位於正確日期流水資料夾，檔名符合公司與工作名稱規則。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationWorkbookServiceTest test`
- [ ] 五格式 OOXML 內容與公式掃描通過。

**Dependencies：** Task 8、Task 9
**Files likely touched：** `QuotationWorkbookService.java`、`QuotationOutputDirectoryService.java`、`template-definitions.json`、對應測試
**Estimated scope：** Medium

## Task 11：實作 Excel 分頁與圖片嵌入

**Description：** 建立模板容量驅動的頁面模型，超量時延續頁面，並將選圖放在所有品項後或獨立圖片頁。

**Acceptance criteria：**

- [ ] 超過各格式單頁容量後自動分頁且品項不遺失、不重複計價。
- [ ] 只有最後一頁顯示正確合計與底部資訊。
- [ ] 圖片等比例縮放，不覆蓋合計、付款資訊、正定聯絡資訊或蓋章。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationWorkbookServiceTest test`
- [ ] artifact-tool 與 Microsoft Excel 渲染至少驗證每格式一份多頁輸出。

**Dependencies：** Task 10
**Files likely touched：** `QuotationWorkbookService.java`、新增頁面模型、`template-definitions.json`、對應測試
**Estimated scope：** Medium

## Task 12：以 Microsoft Excel 匯出 PDF

**Description：** 在受控背景程序中以本機 Excel 將 XLSX 轉為 PDF，保存狀態與錯誤並支援原號重試。

**Acceptance criteria：**

- [ ] 成功時同資料夾產生同基本檔名 PDF，Excel 程序與活頁簿確實關閉。
- [ ] 未安裝 Excel、逾時或匯出錯誤時保留 XLSX 並標記 `PDF_FAILED`。
- [ ] 管理重試使用同一報價、同一路徑與同一流水號。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationPdfServiceTest test`
- [ ] 本機 Microsoft Excel 成功與受控失敗整合測試通過。

**Dependencies：** Task 11
**Files likely touched：** `QuotationPdfService.java`、新增 Excel 轉檔執行器、`QuotationRepository.java`、對應測試
**Estimated scope：** Medium

## Task 13：建立 PDF／圖片安全下載

**Description：** 建立只保存雜湊的下載權杖、7 天期限、撤銷與 no-store 回應，供 PDF 下載及 LINE 圖片原圖／縮圖預覽。

**Acceptance criteria：**

- [ ] 正常權杖可取得正確 MIME；到期、撤銷、錯誤用途或不存在檔案均拒絕。
- [ ] 網址與日誌不含本機相對路徑、原始密鑰或完整權杖。
- [ ] 路徑正規化可阻擋目錄跳脫，縮圖不改寫原圖。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationDownloadControllerTest test`

**Dependencies：** Task 1、Task 9、Task 12
**Files likely touched：** 新增 `QuotationDownloadService.java`、新增下載 Controller、`QuotationRepository.java`、對應測試
**Estimated scope：** Medium

## Task 14：交付 LINE Flex 報價摘要

**Description：** PDF 可用後傳送含公司、工作、單號及金額的 Flex Message，附 PDF 下載按鈕；圖片確認階段傳送 image message。

**Acceptance criteria：**

- [ ] Flex 摘要金額來自正式快照，PDF 按鈕使用有效 HTTPS 權杖。
- [ ] 發送結果、嘗試次數與錯誤摘要被保存，失敗可冪等重試。
- [ ] LINE API 不會收到本機路徑或 PDF 檔案附件。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationDeliveryServiceTest test`

**Dependencies：** Task 5、Task 12、Task 13
**Files likely touched：** 新增 `QuotationDeliveryService.java`、`LineStorageService.java`、`QuotationRepository.java`、對應測試
**Estimated scope：** Medium

## Task 15：建立報價管理 API

**Description：** 提供本機報價列表、詳細預覽、Excel／PDF 下載、PDF 重試、LINE 重試及下載權杖撤銷 API。

**Acceptance criteria：**

- [ ] 可依單號、公司、工作、日期與狀態查詢。
- [ ] 重試只適用合法狀態且不重建流水號或快照。
- [ ] 所有管理操作建立不含機密的稽核紀錄並維持本機限制。

**Verification：**

- [ ] RED/GREEN：`./mvnw.cmd -Dtest=QuotationManagementControllerTest,QuotationAdminControllerTest test`

**Dependencies：** Task 8、Task 12、Task 13、Task 14
**Files likely touched：** `QuotationAdminController.java`、新增管理查詢 Service、`QuotationRepository.java`、對應測試
**Estimated scope：** Medium

## Task 16：擴充本機報價管理頁

**Description：** 在現有管理頁加入正式報價列表、預覽、狀態、檔案下載、PDF／LINE 重試及連結撤銷操作。

**Acceptance criteria：**

- [x] 管理者可完成 Task 15 的所有操作並看到清楚結果及失敗原因。
- [x] 破壞性或外部重試操作需要明確確認，窄螢幕可用；完整實體鍵盤焦點順序另列人工證據。
- [x] 不顯示 AI／LINE 密鑰、下載權杖或原始敏感日誌。

**Verification：**

- [x] MockMvc 靜態契約測試通過。
- [x] 真實瀏覽器桌面與窄螢幕版面、清單 API 及 console error 驗證通過。

**Dependencies：** Task 15
**Files likely touched：** `admin/index.html`、`admin/admin.js`、`admin/admin.css`、頁面契約測試
**Estimated scope：** Medium

## Task 17：端對端驗收與文件同步

**Description：** 逐項執行 SRS AC-01 至 AC-22，驗證五格式 Excel／PDF、LINE 沙盒、本機管理頁、檔案／DB 一致性、可觀測性並更新操作文件。

**Acceptance criteria：**

- [x] AC-01 至 AC-22 每項都有可定位證據或精確的外部／人工限制說明。
- [ ] 五種實際 Excel／PDF 保留固定圖片、蓋章、正定信箱與電話。
- [x] 環境變數、啟動、正常流程、失敗重試及備份文件與實作一致。

**Verification：**

- [x] `./mvnw.cmd test`：309/309 通過。
- [x] `./mvnw.cmd -DskipTests package`
- [ ] artifact-tool、Microsoft Excel、PDF 渲染、真實瀏覽器與 LINE 沙盒檢查完成。

**Dependencies：** Task 1–16、Task 18
**Files likely touched：** `README.md`、`docs/` 對應操作文件、驗收證據輸出
**Estimated scope：** Medium

## Task 18：本機結構化日誌、資源監控與 AI 成本稽核

**Description：** 在程式根目錄 `log/` 建立可輪替的 JSON 結構化日誌，追蹤所有互動流程、外部網路與資源狀況；按單次互動記錄 AI 模型、token 用量、費率快照與本地估算成本。

**Acceptance criteria：**

- [ ] 每個 webhook／管理請求與後續背景工作都有 correlation ID，可串起 AI、DB、檔案、Excel／PDF 與 LINE 交付事件。
- [ ] 網路依賴具有請求數、錯誤、耗時；資源日誌具有 JVM 記憶體、執行緒、連線池與輸出磁碟狀況。
- [ ] AI 成本以 `input/1,000,000 × inputRate + cachedInput/1,000,000 × cachedRate + output/1,000,000 × outputRate` 計算，並保存模型、token、費率、幣別與估算結果。
- [ ] 模型費率可由設定更新；未知模型不得猜價格，需明確記錄 `priceStatus=UNCONFIGURED`。
- [ ] `log/` 已加入 `.gitignore`，日誌可輪替且有保留上限。
- [ ] API Key、LINE token、下載 token、完整個資、訊息全文與圖片內容不會出現在日誌。

**Verification：**

- [ ] RED/GREEN：AI 成本公式、correlation ID 傳遞、遮罩與輪替測試。
- [ ] 執行一筆成功與一筆模擬網路失敗互動，僅靠日誌可定位流程與原因。

**Dependencies：** Task 2、Task 6、Task 12、Task 14
**Files likely touched：** `application.properties`、logback 設定、HTTP／AI／LINE instrumentation、成本服務、`.gitignore`、測試與營運文件
**Estimated scope：** Medium
