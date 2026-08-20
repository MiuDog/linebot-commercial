Unicode true
ManifestDPIAware true
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"
!include "FileFunc.nsh"

!ifndef APP_VERSION
	!error "APP_VERSION is required"
!endif

!ifndef APP_IMAGE
	!error "APP_IMAGE is required"
!endif

!ifndef OUTPUT_FILE
	!error "OUTPUT_FILE is required"
!endif

!ifndef THIRD_PARTY_NOTICES
	!error "THIRD_PARTY_NOTICES is required"
!endif

!ifndef SBOM_FILE
	!error "SBOM_FILE is required"
!endif

!ifndef PUBLISHER
	!error "PUBLISHER is required"
!endif

!ifndef SUPPORT_URL
	!error "SUPPORT_URL is required"
!endif

!define PRODUCT_NAME "LinebotCommercial"
!define DISPLAY_NAME "Linebot Commercial"
!define PRODUCT_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}"
!define START_MENU_FOLDER "$SMPROGRAMS\${DISPLAY_NAME}"

Name "${DISPLAY_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
InstallDirRegKey HKCU "${PRODUCT_KEY}" "InstallLocation"

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey "ProductName" "${DISPLAY_NAME}"
VIAddVersionKey "CompanyName" "${PUBLISHER}"
VIAddVersionKey "FileDescription" "${DISPLAY_NAME} Setup"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "LegalCopyright" "Copyright (c) 2026 ${PUBLISHER}"

Var ExistingInstallDir
Var MaintenanceEditRadio
Var MaintenanceRepairRadio
Var MaintenanceRemoveRadio
Var WasInstalled
Var PurgeCheckbox
Var PurgeState

!define MUI_ABORTWARNING
!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!define MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\modern-uninstall.ico"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "license.rtf"
Page Custom MaintenancePageCreate MaintenancePageLeave
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_WELCOME
UninstPage Custom un.PurgePageCreate un.PurgePageLeave
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "TradChinese"

#region [初始化]

# 方法：偵測既有安裝及非阻擋式 Excel／印表機先決條件。
Function .onInit
	StrCpy $WasInstalled "0"
	ReadRegStr $ExistingInstallDir HKCU "${PRODUCT_KEY}" "InstallLocation"

	${If} $ExistingInstallDir != ""
		StrCpy $WasInstalled "1"
	${EndIf}

	IfSilent InitializationDone

	ReadRegStr $0 HKCR "Excel.Application\CLSID" ""

	${If} $0 == ""
		MessageBox MB_ICONEXCLAMATION|MB_OK "未偵測到 Microsoft Excel。App 仍可安裝，但報價 PDF 功能需安裝 Excel 後才能使用。"
	${EndIf}

	ReadRegStr $1 HKCU "Software\Microsoft\Windows NT\CurrentVersion\Windows" "Device"

	${If} $1 == ""
		MessageBox MB_ICONEXCLAMATION|MB_OK "未偵測到預設印表機。App 仍可安裝，但報價 PDF 功能需可用印表機。"
	${EndIf}

	InitializationDone:
FunctionEnd

# 方法：支援驗收腳本以明確 /PURGE=1 參數測試完整資料清除。
Function un.onInit
	StrCpy $PurgeState ${BST_UNCHECKED}
	${GetParameters} $0
	${GetOptions} $0 "/PURGE=" $1

	${If} $1 == "1"
		StrCpy $PurgeState ${BST_CHECKED}
	${EndIf}
FunctionEnd

#endregion

#region [維護模式]

# 方法：既有安裝時顯示編輯設定、修復升級或移除選項。
Function MaintenancePageCreate
	${If} $WasInstalled != "1"
		Abort
	${EndIf}

	nsDialogs::Create 1018
	Pop $0

	${If} $0 == error
		Abort
	${EndIf}

	${NSD_CreateLabel} 0 0 100% 28u "已偵測到既有安裝，請選擇要執行的維護操作。"
	Pop $0
	${NSD_CreateRadioButton} 0 38u 100% 12u "編輯設定"
	Pop $MaintenanceEditRadio
	${NSD_CreateRadioButton} 0 58u 100% 12u "修復／升級程式檔案（保留設定與資料）"
	Pop $MaintenanceRepairRadio
	${NSD_CreateRadioButton} 0 78u 100% 12u "移除 App"
	Pop $MaintenanceRemoveRadio
	${NSD_Check} $MaintenanceEditRadio
	nsDialogs::Show
FunctionEnd

# 方法：依維護選項開啟設定、進入修復升級或呼叫解除安裝程式。
Function MaintenancePageLeave
	${If} $WasInstalled != "1"
		Return
	${EndIf}

	${NSD_GetState} $MaintenanceEditRadio $0

	${If} $0 == ${BST_CHECKED}
		Exec '"$ExistingInstallDir\${PRODUCT_NAME}.exe" --configure'
		Quit
	${EndIf}

	${NSD_GetState} $MaintenanceRemoveRadio $0

	${If} $0 == ${BST_CHECKED}
		ExecWait '"$ExistingInstallDir\Uninstall.exe"'
		Quit
	${EndIf}
FunctionEnd

#endregion

#region [安裝]

# 方法：停止執行中的 App，寫入完整 app image、捷徑與目前使用者解除安裝資訊。
Section "${DISPLAY_NAME}（必要）" MainSection
	SectionIn RO
	SetShellVarContext current
	RMDir /r "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.update"
	SetOutPath "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.update"

	# 步驟一：先把完整新版寫入產品專屬 staging，既有安裝仍可完整使用。
	File /r "${APP_IMAGE}\*.*"
	File /oname=THIRD-PARTY-NOTICES.md "${THIRD_PARTY_NOTICES}"
	File /oname=sbom.cdx.json "${SBOM_FILE}"
	WriteUninstaller "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.update\Uninstall.exe"

	${If} $WasInstalled == "1"
		IfFileExists "$ExistingInstallDir\${PRODUCT_NAME}.exe" 0 +3
		ExecWait '"$ExistingInstallDir\${PRODUCT_NAME}.exe" --shutdown'
		Sleep 3000
	${EndIf}

	# 步驟二：停止成功後才切換正式目錄，任何 Rename 失敗都保留或回復舊版。
	SetOutPath "$TEMP"
	RMDir /r "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.backup"

	${If} $WasInstalled == "1"
		ClearErrors
		Rename "$INSTDIR" "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.backup"

		${If} ${Errors}
			RMDir /r "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.update"
			MessageBox MB_ICONSTOP|MB_OK "無法停止或備份既有 App，修復／升級已取消，舊版保持不變。"
			Abort
		${EndIf}
	${EndIf}

	ClearErrors
	Rename "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.update" "$INSTDIR"

	${If} ${Errors}
		${If} $WasInstalled == "1"
			Rename "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.backup" "$INSTDIR"
		${EndIf}

		MessageBox MB_ICONSTOP|MB_OK "無法啟用新版 App，已嘗試回復舊版。"
		Abort
	${EndIf}

	RMDir /r "$LOCALAPPDATA\Programs\${PRODUCT_NAME}.backup"
	CreateDirectory "${START_MENU_FOLDER}"
	CreateShortcut "${START_MENU_FOLDER}\${DISPLAY_NAME}.lnk" "$INSTDIR\${PRODUCT_NAME}.exe"
	CreateShortcut "${START_MENU_FOLDER}\編輯設定.lnk" "$INSTDIR\${PRODUCT_NAME}.exe" "--configure"
	CreateShortcut "${START_MENU_FOLDER}\解除安裝.lnk" "$INSTDIR\Uninstall.exe"
	WriteRegStr HKCU "${PRODUCT_KEY}" "DisplayName" "${DISPLAY_NAME}"
	WriteRegStr HKCU "${PRODUCT_KEY}" "DisplayVersion" "${APP_VERSION}"
	WriteRegStr HKCU "${PRODUCT_KEY}" "Publisher" "${PUBLISHER}"
	WriteRegStr HKCU "${PRODUCT_KEY}" "URLInfoAbout" "${SUPPORT_URL}"
	WriteRegStr HKCU "${PRODUCT_KEY}" "HelpLink" "${SUPPORT_URL}"
	WriteRegStr HKCU "${PRODUCT_KEY}" "InstallLocation" "$INSTDIR"
	WriteRegStr HKCU "${PRODUCT_KEY}" "DisplayIcon" "$INSTDIR\${PRODUCT_NAME}.exe"
	WriteRegStr HKCU "${PRODUCT_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
	WriteRegDWORD HKCU "${PRODUCT_KEY}" "NoModify" 0
	WriteRegDWORD HKCU "${PRODUCT_KEY}" "NoRepair" 0

	${If} $WasInstalled != "1"
		${IfNot} ${Silent}
			Exec '"$INSTDIR\${PRODUCT_NAME}.exe" --configure-first-run'
		${EndIf}
	${EndIf}
SectionEnd

# 方法：依使用者勾選建立目前使用者桌面捷徑。
Section /o "建立桌面捷徑" DesktopShortcutSection
	SetShellVarContext current
	CreateShortcut "$DESKTOP\${DISPLAY_NAME}.lnk" "$INSTDIR\${PRODUCT_NAME}.exe"
SectionEnd

#endregion

#region [解除安裝]

# 方法：顯示預設不勾選的完整資料清除選項。
Function un.PurgePageCreate
	${If} ${Silent}
		Abort
	${EndIf}

	nsDialogs::Create 1018
	Pop $0

	${If} $0 == error
		Abort
	${EndIf}

	${NSD_CreateLabel} 0 0 100% 32u "解除安裝預設保留設定、資料庫、輸出與 Log，方便日後重裝。"
	Pop $0
	${NSD_CreateCheckbox} 0 44u 100% 20u "同時刪除本 App 的所有使用者資料（無法復原）"
	Pop $PurgeCheckbox
	nsDialogs::Show
FunctionEnd

# 方法：保存使用者是否明確要求清除產品資料。
Function un.PurgePageLeave
	${NSD_GetState} $PurgeCheckbox $PurgeState
FunctionEnd

# 方法：只移除固定產品目錄，並依明確勾選安全清除固定資料目錄。
Section "Uninstall"
	SetShellVarContext current

	StrCmp $INSTDIR "$LOCALAPPDATA\Programs\${PRODUCT_NAME}" SafeProgramPath UnsafeProgramPath

	UnsafeProgramPath:
		MessageBox MB_ICONSTOP|MB_OK "解除安裝路徑驗證失敗，已停止刪除。"
		Abort

	SafeProgramPath:
		Delete "$DESKTOP\${DISPLAY_NAME}.lnk"
		RMDir /r "${START_MENU_FOLDER}"
		DeleteRegKey HKCU "${PRODUCT_KEY}"
		RMDir /r "$INSTDIR"

	${If} $PurgeState == ${BST_CHECKED}
		StrCpy $0 "$LOCALAPPDATA\${PRODUCT_NAME}"
		StrCmp $0 "$LOCALAPPDATA\${PRODUCT_NAME}" SafeDataPath UnsafeDataPath

		UnsafeDataPath:
			MessageBox MB_ICONSTOP|MB_OK "資料目錄驗證失敗，已保留所有使用者資料。"
			Goto PurgeDone

		SafeDataPath:
			RMDir /r "$0"
	${EndIf}

	PurgeDone:
SectionEnd

#endregion
