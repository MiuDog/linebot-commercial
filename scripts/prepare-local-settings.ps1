param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$environmentPath = Join-Path $projectRoot ".env"
$secretRoot = Join-Path $projectRoot "secrets"
$settings = @{}
$utf8 = [System.Text.UTF8Encoding]::new($false)

# 檔案系統：寫入前確認根路徑不是連結，避免透過父層連結寫到專案之外。
foreach ($path in @($environmentPath, $secretRoot)) {
	if ((Test-Path -LiteralPath $path) -and
		((Get-Item -LiteralPath $path -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
		throw "本地設定路徑不可為連結。"
	}
}

# 方法：讀取單行 dotenv 值，不執行 shell 插值，也不輸出機密。
function Read-SettingsFile {
	param([string]$Path)
	$values = @{}
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }

	foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
		if ($line -match '^\s*([A-Z0-9_]+)\s*=(.*)$') {
			$values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
		}
	}
	return $values
}

# 方法：僅修復預期名稱的空 Secret 資料夾，不遞迴刪除或跟隨連結。
function Prepare-SecretPath {
	param([string]$Name)
	$path = Join-Path $secretRoot $Name
	if (Test-Path -LiteralPath $path) {
		$entry = Get-Item -LiteralPath $path -Force
		if ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
			throw "Secret 路徑不可為連結：$Name"
		}
		if ($entry.PSIsContainer) {
			# 檔案系統：Delete(false) 只允許空資料夾，既有資料絕不遞迴移除。
			[System.IO.Directory]::Delete($path, $false)
		}
	}
	return $path
}

# 方法：補齊缺少的設定，保留使用者已設定的每一個值。
$settings = Read-SettingsFile $environmentPath
$defaults = Read-SettingsFile (Join-Path $projectRoot ".env.example")
$additions = @()
foreach ($name in $defaults.Keys | Sort-Object) {
	if (-not $settings.ContainsKey($name)) {
		$settings[$name] = $defaults[$name]
		$additions += "$name=$($defaults[$name])"
	}
}
if ($additions.Count -gt 0) {
	[System.IO.File]::AppendAllText($environmentPath, "`n" + ($additions -join "`n") + "`n", $utf8)
}

# 檔案系統：將 Secret 保存在 Compose 指定位置，禁止自動寫入專案以外。
$configuredSecretRoot = $settings["SECRETS_DIR"]
if ($configuredSecretRoot -and $configuredSecretRoot -notin @("./secrets", ".\secrets", "secrets")) {
	throw "自訂 SECRETS_DIR 請手動準備；此工具只操作本專案 secrets/。"
}
[System.IO.Directory]::CreateDirectory($secretRoot) | Out-Null
$secretDefinitions = @{
	"database-password" = @("DATABASE_PASSWORD", $true)
	"object-storage-access-key" = @("OBJECT_STORAGE_ACCESS_KEY", $true)
	"object-storage-secret-key" = @("OBJECT_STORAGE_SECRET_KEY", $true)
	"admin-token" = @("ADMIN_TOKEN", $true)
	"line-channel-token" = @("LINE_BOT_CHANNEL_TOKEN", $false)
	"line-channel-secret" = @("LINE_BOT_CHANNEL_SECRET", $false)
}
if (Test-Path (Join-Path $projectRoot "SPEC-portable-pdf-rendering.md")) {
	$secretDefinitions["quotation-postback-secret"] = @("QUOTATION_POSTBACK_SECRET", $true)
	$secretDefinitions["quotation-image-link-secret"] = @("QUOTATION_IMAGE_LINK_SECRET", $true)
	$secretDefinitions["ai-api-key"] = @("AI_API_KEY", $false)
}
else {
	$secretDefinitions["assets-sync-token"] = @("ASSETS_SYNC_TOKEN", $true)
}
if ($settings["CLOUDFLARED_TOKEN"]) {
	$secretDefinitions["cloudflared-token"] = @("CLOUDFLARED_TOKEN", $false)
}

# 密碼學：只為缺少的內部憑證產生 256-bit 隨機值，外部憑證只能沿用使用者設定。
foreach ($name in $secretDefinitions.Keys | Sort-Object) {
	$path = Prepare-SecretPath $name
	if ((Test-Path -LiteralPath $path -PathType Leaf) -and
		-not [string]::IsNullOrWhiteSpace([System.IO.File]::ReadAllText($path))) {
		Write-Output "保留既有 Secret：$name"
		continue
	}
	$definition = $secretDefinitions[$name]
	$value = $settings[$definition[0]]
	if ([string]::IsNullOrWhiteSpace($value) -and $definition[1]) {
		$bytes = New-Object byte[] 32
		$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
		try { $generator.GetBytes($bytes) }
		finally { $generator.Dispose() }
		$value = [System.BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
	}
	[System.IO.File]::WriteAllText($path, [string]$value, $utf8)
	if ([string]::IsNullOrWhiteSpace($value)) {
		Write-Output "尚待填寫外部憑證：$name"
	}
	else {
		Write-Output "已準備 Secret：$name"
	}
}
Write-Output "本地設定已準備；上線前仍需有效的 LINE、AI（Commercial）與公開 HTTPS 設定。"
