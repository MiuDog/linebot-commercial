param(
	[Parameter(Mandatory = $true)]
	[string]$InstallerPath,
	[switch]$RequireSignature,
	[switch]$ExecuteLifecycle,
	[switch]$TestPurge
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ResolvedInstaller = [System.IO.Path]::GetFullPath($InstallerPath)
$ProductName = "LinebotCommercial"
$ServiceName = "${ProductName}Service"
$InstallRoot = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA "Programs\$ProductName"))
$DataRoot = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA $ProductName))
$LauncherPath = Join-Path $InstallRoot "$ProductName.exe"
$ServiceLauncherPath = Join-Path $InstallRoot "$ServiceName.exe"
$UninstallerPath = Join-Path $InstallRoot "Uninstall.exe"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$InstalledNoticesPath = Join-Path $InstallRoot "THIRD-PARTY-NOTICES.md"
$InstalledSbomPath = Join-Path $InstallRoot "sbom.cdx.json"
$EvidenceRoot = [System.IO.Path]::GetFullPath((Join-Path (Split-Path $ResolvedInstaller -Parent) "installer-evidence"))
$EvidencePath = Join-Path $EvidenceRoot "installer-smoke.json"

#endregion

#region [方法]

# 方法：執行外部安裝或解除安裝程序並驗證 exit code。
function Invoke-InstallerProcess {
	param(
		[string]$Executable,
		[string[]]$Arguments
	)

	# 外部函式：以隱藏視窗啟動 Setup 或 Uninstaller，避免 smoke test 留下互動畫面。
	$Process = Start-Process -FilePath $Executable -ArgumentList $Arguments -PassThru -WindowStyle Hidden

	# 外部函式：限制單一安裝階段最多三分鐘，避免 CI 被不可見提示永久阻擋。
	$Completed = $Process.WaitForExit(180000)

	if (-not $Completed) {
		# 外部函式：逾時時終止本輪產品安裝程序及其子程序，再回報明確失敗。
		$Process.Kill($true)
		throw "安裝程序逾時：$Executable"
	}

	if ($Process.ExitCode -ne 0) {
		throw "安裝程序失敗：$Executable，exit code：$($Process.ExitCode)"
	}
}

# 方法：驗證目前路徑只指向固定產品程式或資料根目錄。
function Assert-ProductPath {
	param(
		[string]$Candidate,
		[string]$Expected
	)

	if ([System.IO.Path]::GetFullPath($Candidate) -ne [System.IO.Path]::GetFullPath($Expected)) {
		throw "產品路徑驗證失敗：$Candidate"
	}
}

# 方法：等待 Uninstaller 完成自我刪除，避免程序結束與目錄移除之間的競態。
function Wait-ProductPathRemoval {
	param(
		[string]$Path
	)

	for ($Attempt = 0; $Attempt -lt 300; $Attempt++) {
		if (-not (Test-Path -LiteralPath $Path)) {
			return

		}

		# 外部函式：短暫等待 Uninstaller 完成 Windows 延後的檔案刪除。
		Start-Sleep -Milliseconds 100
	}
}

# 方法：安全讀取背景服務登入啟動值，不把尚未註冊或已移除視為 PowerShell 錯誤。
function Get-AutoStartValue {
	# 外部平台：讀取目前使用者的 Windows 登入啟動設定，供安裝與卸載狀態比對。
	$RunProperties = Get-ItemProperty -Path $RunKey -ErrorAction SilentlyContinue

	if ($null -eq $RunProperties) {
		return $null

	}

	$RunProperty = $RunProperties.PSObject.Properties[$ServiceName]

	if ($null -eq $RunProperty) {
		return $null

	}

	return [string]$RunProperty.Value
}

#endregion

#region [主流程]

# 步驟一：執行不改變系統的 Setup 檔案、版本、雜湊與簽章檢查。
if (-not (Test-Path -LiteralPath $ResolvedInstaller -PathType Leaf)) {
	throw "找不到 Setup.exe：$ResolvedInstaller"
}

$InstallerItem = Get-Item -LiteralPath $ResolvedInstaller
$InstallerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResolvedInstaller).Hash
$Signature = Get-AuthenticodeSignature -LiteralPath $ResolvedInstaller

if ($InstallerItem.Length -lt 1MB) {
	throw "Setup.exe 大小異常，可能未包含 app image。"
}

if ($RequireSignature -and $Signature.Status -ne "Valid") {
	throw "正式驗收要求有效 Authenticode 簽章，目前狀態：$($Signature.Status)"
}

$Evidence = [ordered]@{
	installer = $ResolvedInstaller
	sha256 = $InstallerHash
	sizeBytes = $InstallerItem.Length
	signatureStatus = [string]$Signature.Status
	staticVerified = $true
	lifecycleExecuted = $false
	defaultUninstallPreservedData = $false
	purgeRemovedData = $false
	serviceLauncherInstalled = $false
	autoStartRegistered = $false
	autoStartRemoved = $false
	noticesInstalled = $false
	sbomInstalled = $false
}

# 步驟二：只有明確指定時才在目前使用者環境執行安裝生命週期。
if ($ExecuteLifecycle) {
	Assert-ProductPath -Candidate $InstallRoot -Expected (Join-Path $env:LOCALAPPDATA "Programs\$ProductName")
	Assert-ProductPath -Candidate $DataRoot -Expected (Join-Path $env:LOCALAPPDATA $ProductName)

	if (Test-Path -LiteralPath $InstallRoot) {
		throw "偵測到既有產品安裝，為保護使用者狀態已停止 smoke test：$InstallRoot"
	}

	$DataExistedBeforeTest = Test-Path -LiteralPath $DataRoot
	New-Item -ItemType Directory -Path $DataRoot -Force | Out-Null
	$PreservationMarker = Join-Path $DataRoot "installer-smoke-preserve.marker"
	Set-Content -LiteralPath $PreservationMarker -Value "preserve" -Encoding UTF8

	# 步驟三：靜默執行首次安裝與再次修復，並確認單一 launcher 與 uninstaller。
	Invoke-InstallerProcess -Executable $ResolvedInstaller -Arguments @("/S")

	if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
		throw "首次安裝後找不到 App launcher。"
	}

	if (-not (Test-Path -LiteralPath $ServiceLauncherPath -PathType Leaf)) {
		throw "首次安裝後找不到背景 Service launcher。"
	}

	# 外部平台：讀取目前使用者的 Windows 登入啟動設定，確認背景服務已完成註冊。
	$AutoStartValue = Get-AutoStartValue
	$ExpectedAutoStartValue = "`"$ServiceLauncherPath`""

	if ($AutoStartValue -ne $ExpectedAutoStartValue) {
		throw "背景 Service 的 Windows 登入啟動設定不正確。"
	}

	if (-not (Test-Path -LiteralPath $InstalledNoticesPath -PathType Leaf)) {
		throw "首次安裝後找不到第三方授權聲明。"
	}

	if (-not (Test-Path -LiteralPath $InstalledSbomPath -PathType Leaf)) {
		throw "首次安裝後找不到 CycloneDX SBOM。"
	}

	$Evidence.noticesInstalled = $true
	$Evidence.sbomInstalled = $true
	$Evidence.serviceLauncherInstalled = $true
	$Evidence.autoStartRegistered = $true

	Invoke-InstallerProcess -Executable $ResolvedInstaller -Arguments @("/S")

	if (-not (Test-Path -LiteralPath $UninstallerPath -PathType Leaf)) {
		throw "修復後找不到 Uninstaller。"
	}

	# 步驟四：預設解除安裝必須移除程式但保留使用者資料 marker。
	Invoke-InstallerProcess -Executable $UninstallerPath -Arguments @("/S")
	Wait-ProductPathRemoval -Path $InstallRoot

	if (Test-Path -LiteralPath $InstallRoot) {
		throw "解除安裝後產品程式目錄仍存在。"
	}

	if (-not (Test-Path -LiteralPath $PreservationMarker -PathType Leaf)) {
		throw "預設解除安裝錯誤刪除了使用者資料。"
	}

	# 外部平台：再次讀取 Windows 登入啟動設定，確認解除安裝沒有留下失效入口。
	$AutoStartValue = Get-AutoStartValue

	if ($null -ne $AutoStartValue) {
		throw "解除安裝後仍殘留背景 Service 的 Windows 登入啟動設定。"
	}

	$Evidence.lifecycleExecuted = $true
	$Evidence.defaultUninstallPreservedData = $true
	$Evidence.autoStartRemoved = $true

	# 步驟五：只有資料目錄原本不存在且明確指定時才驗證完整 purge。
	if ($TestPurge) {
		if ($DataExistedBeforeTest) {
			throw "資料目錄在測試前已存在，為避免刪除使用者資料而拒絕 purge 測試。"
		}

		Invoke-InstallerProcess -Executable $ResolvedInstaller -Arguments @("/S")
		Invoke-InstallerProcess -Executable $UninstallerPath -Arguments @("/S", "/PURGE=1")
		Wait-ProductPathRemoval -Path $DataRoot

		if (Test-Path -LiteralPath $DataRoot) {
			throw "明確 purge 後產品資料目錄仍存在。"
		}

		$Evidence.purgeRemovedData = $true
	}
	else {
		Remove-Item -LiteralPath $PreservationMarker -Force

		if (-not $DataExistedBeforeTest) {
			Remove-Item -LiteralPath $DataRoot -Force
		}
	}
}

# 步驟六：輸出不含機密的可定位 JSON 驗收證據。
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
$Evidence | ConvertTo-Json | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
Write-Information "Installer 驗收證據：$EvidencePath" -InformationAction Continue

#endregion
