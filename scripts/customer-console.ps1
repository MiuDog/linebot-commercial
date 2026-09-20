param(
	[ValidateSet("Menu", "Setup", "Validate", "Start", "Status", "Diagnose", "Logs", "Stop")]
	[string]$Action = "Menu",
	[string]$ProjectRootOverride = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$OperationExitCode = 0

$ProductName = "Linebot Commercial"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not [string]::IsNullOrWhiteSpace($ProjectRootOverride)) {
	# 外部路徑 API：將測試或支援工具指定的根目錄正規化，避免相對路徑讀錯設定。
	$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRootOverride)
}
$EnvironmentPath = Join-Path $ProjectRoot ".env"
$EnvironmentExamplePath = Join-Path $ProjectRoot ".env.example"

#region [設定讀寫]

# 方法：讀取 dotenv 設定，忽略註解與空白行並保留最後一個有效值。
function Read-CustomerEnvironment {
	param(
		[string]$Path
	)

	$values = @{}
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }

	# 外部檔案 API：逐行讀取設定，避免將整份內容輸出到主控台。
	foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
		$trimmed = $line.Trim()
		if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
		if ($trimmed.StartsWith("#")) { continue }

		$separator = $trimmed.IndexOf("=")
		if ($separator -lt 1) { continue }

		$key = $trimmed.Substring(0, $separator).Trim()
		$value = $trimmed.Substring($separator + 1).Trim()
		$values[$key] = $value
	}

	return $values
}

# 方法：更新單一 dotenv 欄位並以 UTF-8 無 BOM 保存。
function Set-CustomerEnvironmentValue {
	param(
		[string]$Path,
		[string]$Key,
		[string]$Value
	)

	# 外部檔案 API：讀取現有行以保留使用者註解與欄位順序。
	$lines = [System.Collections.Generic.List[string]]::new([System.IO.File]::ReadAllLines($Path))
	$prefix = "$Key="
	$updated = $false
	for ($index = 0; $index -lt $lines.Count; $index++) {
		if (-not $lines[$index].StartsWith($prefix, [System.StringComparison]::Ordinal)) { continue }

		$lines[$index] = "$prefix$Value"
		$updated = $true
		break
	}

	if (-not $updated) {
		$lines.Add("$prefix$Value")
	}

	# 外部檔案 API：使用 UTF-8 無 BOM 寫回 Docker 可直接解析的設定檔。
	[System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
}

# 方法：將 SecureString 短暫轉成記憶體文字，呼叫端必須立即保存且不得輸出。
function ConvertFrom-CustomerSecureString {
	param(
		[securestring]$Value
	)

	# 外部平台 API：取得安全字串指標，並在 finally 中清除非受控記憶體。
	$pointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
	try {
		return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
	}
	finally {
		[System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
	}
}

# 方法：產生只用於本機基礎設施的高強度隨機值。
function New-CustomerSecret {
	param(
		[int]$ByteCount = 32
	)

	$bytes = [byte[]]::new($ByteCount)
	$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
	try {
		# 外部密碼學 API：使用 Windows PowerShell 5.1 可用的安全亂數填入 Secret 位元組。
		$generator.GetBytes($bytes)
	}
	finally {
		$generator.Dispose()
	}

	# 外部轉換 API：以 Windows PowerShell 5.1 可用的方法產生不含分隔符號的小寫十六進位值。
	return ([System.BitConverter]::ToString($bytes) -replace "-", "").ToLowerInvariant()
}

# 方法：安全保存單行 Secret，且不在畫面或 Log 顯示內容。
function Save-CustomerSecret {
	param(
		[string]$Name,
		[string]$Value
	)

	$secretDirectory = Join-Path $ProjectRoot "secrets"
	$secretPath = Join-Path $secretDirectory $Name

	# 外部檔案 API：建立本機 Secret 目錄並以 UTF-8 無 BOM 保存單行內容。
	[System.IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
	[System.IO.File]::WriteAllText($secretPath, $Value, [System.Text.UTF8Encoding]::new($false))

	# 外部檔案 API：立即讀回比對，只有實際保存成功才能顯示完成。
	if ([System.IO.File]::ReadAllText($secretPath) -cne $Value) {
		throw [System.IO.IOException]::new("Secret 保存後比對失敗。")
	}
}

# 方法：詢問一般設定，空白輸入時保留目前值。
function Read-CustomerValue {
	param(
		[string]$Label,
		[string]$CurrentValue
	)

	$displayValue = $CurrentValue
	if ([string]::IsNullOrWhiteSpace($displayValue)) {
		$displayValue = "未設定"
	}
	$value = Read-Host "$Label（目前：$displayValue，直接 Enter 保留）"
	if ([string]::IsNullOrWhiteSpace($value)) { return $CurrentValue }

	return $value.Trim()
}

# 方法：取得商用機啟動時必須存在的 Secret 定義。
function Get-RequiredSecretDefinitions {
	param(
		[hashtable]$Environment
	)

	$definitions = [System.Collections.Generic.List[object]]::new()
	$definitions.Add([pscustomobject]@{ Name = "database-password"; Label = "資料庫密碼"; MinimumLength = 16; Generated = $true; Empty = $false })
	$definitions.Add([pscustomobject]@{ Name = "object-storage-access-key"; Label = "物件儲存帳號"; MinimumLength = 8; Generated = $true; Empty = $false })
	$definitions.Add([pscustomobject]@{ Name = "object-storage-secret-key"; Label = "物件儲存密碼"; MinimumLength = 32; Generated = $true; Empty = $false })
	$definitions.Add([pscustomobject]@{ Name = "line-channel-token"; Label = "LINE Channel Access Token"; MinimumLength = 20; Generated = $false; Empty = $false })
	$definitions.Add([pscustomobject]@{ Name = "line-channel-secret"; Label = "LINE Channel Secret"; MinimumLength = 16; Generated = $false; Empty = $false })
	$definitions.Add([pscustomobject]@{ Name = "admin-token"; Label = "管理 API Token"; MinimumLength = 32; Generated = $true; Empty = $false })
	$aiConfigured = -not [string]::IsNullOrWhiteSpace($Environment["AI_API_URL"]) -or -not [string]::IsNullOrWhiteSpace($Environment["AI_MODEL"])
	if ($aiConfigured) {
		$definitions.Add([pscustomobject]@{ Name = "ai-api-key"; Label = "AI API Key"; MinimumLength = 10; Generated = $false; Empty = $false })
	}
	else {
		$definitions.Add([pscustomobject]@{ Name = "ai-api-key"; Label = "AI API Key（未啟用 AI）"; MinimumLength = 0; Generated = $true; Empty = $true })
	}
	$definitions.Add([pscustomobject]@{ Name = "quotation-postback-secret"; Label = "報價操作簽章"; MinimumLength = 32; Generated = $true; Empty = $false })
	if ($Environment["AI_WORKFLOW_ENABLED"] -eq "true") {
		foreach ($role in @("TEXT", "VISION", "ESCALATION")) {
			$roleUrl = $Environment["AI_${role}_API_URL"]
			if (-not [string]::IsNullOrWhiteSpace($roleUrl) -and $roleUrl.Trim() -cne $Environment["AI_API_URL"]) {
				$definitions.Add([pscustomobject]@{ Name = "ai-$($role.ToLowerInvariant())-api-key"; Label = "$role 模型 API Key"; MinimumLength = 10; Generated = $false; Empty = $false })
			}
		}
	}
	$definitions.Add([pscustomobject]@{ Name = "quotation-image-link-secret"; Label = "報價圖片連結簽章"; MinimumLength = 32; Generated = $true; Empty = $false })

	if ($Environment["TUNNEL_ENABLED"] -eq "true") {
		$definitions.Add([pscustomobject]@{ Name = "cloudflared-token"; Label = "Cloudflare Tunnel Token"; MinimumLength = 20; Generated = $false; Empty = $false })
	}

	return $definitions
}

#endregion

#region [設定驗證]

# 方法：建立不包含 Secret 內容的設定問題。
function New-ConfigurationIssue {
	param(
		[string]$Field,
		[string]$Cause,
		[string]$Resolution
	)

	return [pscustomobject]@{
		Field = $Field
		Cause = $Cause
		Resolution = $Resolution
	}
}

# 方法：驗證客戶設定是否足以安全啟動，任何問題都在 Docker 建立容器前阻擋。
function Test-CustomerConfiguration {
	$issues = [System.Collections.Generic.List[object]]::new()
	if (-not (Test-Path -LiteralPath $EnvironmentPath -PathType Leaf)) {
		$issues.Add((New-ConfigurationIssue ".env" "尚未完成首次設定。" "雙擊 linebot.cmd，選擇「首次設定／修改設定」。"))
		return [pscustomobject]@{ IsValid = $false; Issues = $issues; Environment = @{} }
	}

	$environment = Read-CustomerEnvironment $EnvironmentPath
	$companyId = $environment["COMPANY_ID"]
	if ([string]::IsNullOrWhiteSpace($companyId) -or $companyId -eq "company-local" -or $companyId -notmatch "^[a-z0-9][a-z0-9-]{1,62}$") {
		$issues.Add((New-ConfigurationIssue "公司代碼" "COMPANY_ID 未設定、仍為範例值，或含有不支援字元。" "使用 2–63 個小寫英文字母、數字或連字號，例如 zhengding。"))
	}

	$publicBaseUrl = $environment["PUBLIC_BASE_URL"]
	$publicUri = $null
	$validPublicUrl = [System.Uri]::TryCreate($publicBaseUrl, [System.UriKind]::Absolute, [ref]$publicUri)
	if (-not $validPublicUrl -or $publicUri.Scheme -ne "https" -or $publicUri.AbsolutePath -ne "/" -or $publicBaseUrl.EndsWith("/") -or $publicUri.Host.EndsWith("example.com")) {
		$issues.Add((New-ConfigurationIssue "公開網址" "PUBLIC_BASE_URL 必須是正式 HTTPS 網址、不能含路徑或結尾斜線，也不能保留 example.com。" "填入 Cloudflare Tunnel 綁定的完整網址，例如 https://commercial.company.com。"))
	}

	$port = 0
	if (-not [int]::TryParse($environment["APP_PORT"], [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
		$issues.Add((New-ConfigurationIssue "本機連接埠" "APP_PORT 不是 1–65535 的有效數字。" "文書機建議保留 8089；商用機使用 8088，兩者不可相同。"))
	}

	$bucket = $environment["OBJECT_STORAGE_BUCKET"]
	if ([string]::IsNullOrWhiteSpace($bucket) -or $bucket -notmatch "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") {
		$issues.Add((New-ConfigurationIssue "Bucket 名稱" "OBJECT_STORAGE_BUCKET 不符合 S3 命名規則。" "使用 3–63 個小寫英文字母、數字、句點或連字號。"))
	}

	$cloudflareProtocol = $environment["CLOUDFLARED_PROTOCOL"]
	if ([string]::IsNullOrWhiteSpace($cloudflareProtocol)) {
		$cloudflareProtocol = "http2"
	}
	if ($cloudflareProtocol -notin @("auto", "http2", "quic")) {
		$issues.Add((New-ConfigurationIssue "Cloudflare 通訊協定" "CLOUDFLARED_PROTOCOL 只支援 auto、http2 或 quic。" "公司 VPN 環境請使用預設 http2；確認網路允許 TCP 7844。"))
	}

	$aiApiUrl = $environment["AI_API_URL"]
	$aiModel = $environment["AI_MODEL"]
	$aiUri = $null
	$aiUrlValid = [System.Uri]::TryCreate($aiApiUrl, [System.UriKind]::Absolute, [ref]$aiUri)
	if ([string]::IsNullOrWhiteSpace($aiApiUrl) -ne [string]::IsNullOrWhiteSpace($aiModel)) {
		$issues.Add((New-ConfigurationIssue "AI 設定" "AI_API_URL 與 AI_MODEL 必須同時設定或同時留空。" "需要 AI 報價時填入兩者；不使用 AI 時將兩者都留空。"))
	}
	elseif (-not [string]::IsNullOrWhiteSpace($aiApiUrl) -and (-not $aiUrlValid -or $aiUri.Scheme -ne "https")) {
		$issues.Add((New-ConfigurationIssue "AI API 網址" "AI_API_URL 不是有效的 HTTPS 網址。" "填入供應商提供的 HTTPS API 基底網址，不要填管理後台網址。"))
	}

	# 各角色端點都需檢查，避免設定精靈接受官網或管理後台網址。
	foreach ($urlName in @("AI_API_URL", "AI_TEXT_API_URL", "AI_VISION_API_URL", "AI_ESCALATION_API_URL")) {
		$configuredUrl = $environment[$urlName]
		if ([string]::IsNullOrWhiteSpace($configuredUrl)) { continue }
		if ($urlName -ne "AI_API_URL" -and $environment["AI_WORKFLOW_ENABLED"] -ne "true") { continue }
		$endpoint = $null
		$validEndpoint = [System.Uri]::TryCreate($configuredUrl, [System.UriKind]::Absolute, [ref]$endpoint)
		if (-not $validEndpoint -or $endpoint.Scheme -ne "https" -or
			$endpoint.Host -in @("openai.com", "www.openai.com", "platform.openai.com", "chatgpt.com", "www.chatgpt.com", "chat.openai.com")) {
			$issues.Add((New-ConfigurationIssue $urlName "不是可用的 HTTPS API 網址，或誤填網站／管理後台。" "OpenAI Platform 使用 https://api.openai.com/v1；其他供應商使用其 API 網址。"))
		}
	}

	$secretDirectory = Join-Path $ProjectRoot "secrets"
	foreach ($definition in Get-RequiredSecretDefinitions $environment) {
		$secretPath = Join-Path $secretDirectory $definition.Name
		if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) {
			$issues.Add((New-ConfigurationIssue $definition.Label "必要 Secret 檔案不存在。" "重新執行首次設定；控制台會安全產生或要求輸入此欄位。"))
			continue
		}

		# 外部檔案 API：只在記憶體檢查格式與長度，不顯示 Secret 原文。
		$secretValue = [System.IO.File]::ReadAllText($secretPath)
		$normalizedSecret = $secretValue.TrimEnd([char[]]"`r`n")
		$containsEmbeddedLineBreak = $normalizedSecret.Contains("`r") -or $normalizedSecret.Contains("`n")
		$containsBoundaryWhitespace = $normalizedSecret -ne $normalizedSecret.Trim()
		if ($containsEmbeddedLineBreak -or $containsBoundaryWhitespace -or $normalizedSecret.Length -lt $definition.MinimumLength) {
			$issues.Add((New-ConfigurationIssue $definition.Label "Secret 為空、含有多行／前後空白，或長度不足。" "重新執行首次設定並輸入有效值；一般編輯器自動加入的單一尾端換行可以保留。"))
		}
	}

	return [pscustomobject]@{ IsValid = $issues.Count -eq 0; Issues = $issues; Environment = $environment }
}

# 方法：顯示設定驗證結果，每項都附原因與可執行解法。
function Show-ConfigurationValidation {
	param(
		[object]$Validation
	)

	if ($Validation.IsValid) {
		Write-Host "[通過] 本機設定格式完整；尚未驗證 API 金鑰與模型權限。" -ForegroundColor Green
		$environment = $Validation.Environment
		if ($environment["AI_WORKFLOW_ENABLED"] -eq "true") {
			Write-Host "[AI 路由] Harness 已啟用：圖片 → VISION；文字 → TEXT；驗證修復 → ESCALATION。"
			$models = @()
			foreach ($role in @("TEXT", "VISION", "ESCALATION")) {
				$model = $environment["AI_${role}_MODEL"]
				if ([string]::IsNullOrWhiteSpace($model)) { $model = $environment["AI_MODEL"] }
				$models += $model
				Write-Host "[AI 路由] $role = $model"
			}
			if (@($models | Select-Object -Unique).Count -eq 1) {
				Write-Host "[注意] 三個角色目前使用同一模型；請在首次設定指定不同角色模型。" -ForegroundColor Yellow
			}
		}
		else {
			Write-Host "[AI 路由] Harness 未啟用，沿用單模型 AI_MODEL。"
		}
		return
	}

	Write-Host "[阻擋] 尚有 $($Validation.Issues.Count) 項設定需要處理；目前不會建立或變更容器。" -ForegroundColor Red
	foreach ($issue in $Validation.Issues) {
		Write-Host "`n欄位：$($issue.Field)" -ForegroundColor Yellow
		Write-Host "原因：$($issue.Cause)"
		Write-Host "解法：$($issue.Resolution)"
	}
}

#endregion

#region [Docker 操作]

# 方法：確認 Docker CLI、Compose 與 Linux 引擎均可用，並提供客戶可理解的處理方式。
function Assert-DockerReady {
	if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
		Write-Host "原因：找不到 Docker。" -ForegroundColor Red
		Write-Host "解法：安裝 Docker Desktop，完成後重新開機再執行本控制台。"
		return $false
	}

	# 外部 Docker API：確認背景引擎已完成啟動，而不只檢查桌面程式是否存在。
	& docker info --format "{{.OSType}}" *> $null
	if ($LASTEXITCODE -ne 0) {
		Write-Host "原因：Docker Desktop 已安裝，但背景引擎尚未啟動或目前帳號無法連線。" -ForegroundColor Red
		Write-Host "解法：開啟 Docker Desktop，等左下角顯示 Engine running；若仍失敗，重新啟動 Docker Desktop。"
		return $false
	}

	# 外部 Docker API：確認目前版本具備 Compose v2。
	& docker compose version *> $null
	if ($LASTEXITCODE -ne 0) {
		Write-Host "原因：Docker Compose v2 無法使用。" -ForegroundColor Red
		Write-Host "解法：更新 Docker Desktop，並確認設定中的 Use Docker Compose V2 已啟用。"
		return $false
	}

	return $true
}

# 方法：確認本機連接埠可供新容器使用，避免誤連到另一套機器人或其他程式。
function Test-CustomerLocalPortAvailable {
	param(
		[int]$Port
	)

	$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
	try {
		# 外部網路 API：短暫綁定 loopback 連接埠，確認 Docker 啟動前沒有其他程序占用。
		$listener.Start()
		return $true
	}
	catch [System.Net.Sockets.SocketException] {
		return $false
	}
	finally {
		$listener.Stop()
	}
}

# 方法：在通過全部前置驗證後才建立並啟動服務。
function Invoke-ComposeUp {
	param(
		[bool]$TunnelEnabled
	)

	$arguments = @("compose")
	if ($TunnelEnabled) {
		$arguments += @("--profile", "tunnel")
	}
	$arguments += @("up", "--build", "--wait")

	# 外部 Docker API：建立、啟動並等待所有必要服務健康。
	& docker @arguments
	if ($LASTEXITCODE -ne 0) {
		Write-Host "`n原因：服務未能全部進入健康狀態。" -ForegroundColor Red
		Write-Host "解法：回到主選單執行「連線與環境診斷」，再執行「查看 App 紀錄」。"
		return $false
	}

	# 外部 Docker API：Secret 內容異動不一定觸發 Compose 重建，固定重建 App 以重新載入憑證。
	& docker compose up -d --no-deps --no-build --force-recreate --wait app
	if ($LASTEXITCODE -ne 0) {
		Write-Host "[未完成] App 未能重新載入設定；請執行連線診斷，不能視為新金鑰已生效。" -ForegroundColor Red
		return $false
	}

	Write-Host "`n[完成] $ProductName 已啟動。" -ForegroundColor Green
	$environment = Read-CustomerEnvironment $EnvironmentPath
	Write-Host "本機狀態：http://127.0.0.1:$($environment["APP_PORT"])/readyz"
	return $true
}

# 方法：驗證設定後啟動服務，避免 Docker 先建立半套容器。
function Start-CustomerService {
	$validation = Test-CustomerConfiguration
	Show-ConfigurationValidation $validation
	if (-not $validation.IsValid) {
		$script:OperationExitCode = 2
		return
	}

	if (-not (Assert-DockerReady)) {
		$script:OperationExitCode = 4
		return
	}

	# 外部 Docker API：本專案 App 已運行時允許重複執行啟動，否則先排除連接埠衝突。
	$runningServices = @(& docker compose ps --status running --services 2> $null)
	$port = [int]$validation.Environment["APP_PORT"]
	if ($runningServices -notcontains "app" -and -not (Test-CustomerLocalPortAvailable $port)) {
		Write-Host "原因：本機連接埠 $port 已被另一個程式占用，無法啟動 Commercial。" -ForegroundColor Red
		Write-Host "解法：Commercial 建議使用 8088、Document 使用 8089；修改設定後再啟動。"
		$script:OperationExitCode = 6
		return
	}

	$tunnelEnabled = $validation.Environment["TUNNEL_ENABLED"] -eq "true"
	$started = Invoke-ComposeUp $tunnelEnabled
	if (-not $started) { $script:OperationExitCode = 5 }
}

# 方法：以客戶可辨識的名稱顯示每個容器目前狀態。
function Show-CustomerStatus {
	if (-not (Assert-DockerReady)) { return }

	Write-Host "`n服務狀態：" -ForegroundColor Cyan
	# 外部 Docker API：列出本產品容器、健康狀態與連接埠。
	& docker compose ps
	if ($LASTEXITCODE -ne 0) {
		Write-Host "原因：無法讀取本產品服務狀態。" -ForegroundColor Red
		Write-Host "解法：確認目前資料夾完整且 .env 未被移動，再執行診斷。"
	}
}

# 方法：顯示 App 最近紀錄，避免資料庫與儲存服務雜訊淹沒主要錯誤。
function Show-CustomerLogs {
	if (-not (Assert-DockerReady)) { return }

	Write-Host "顯示 App 最近 200 行紀錄；按 Ctrl+C 可停止持續查看。" -ForegroundColor Cyan
	# 外部 Docker API：只追蹤 App 服務並保留時間戳，方便對照問題發生時間。
	& docker compose logs --tail 200 --timestamps --follow app
}

# 方法：安全停止容器但保留資料庫與圖片資料卷，避免客戶誤刪資料。
function Stop-CustomerService {
	if (-not (Assert-DockerReady)) { return }

	# 外部 Docker API：停止並移除執行容器與網路，但刻意不傳入 --volumes。
	& docker compose down --remove-orphans
	if ($LASTEXITCODE -eq 0) {
		Write-Host "[完成] 服務已停止，資料庫與圖片資料仍保留。" -ForegroundColor Green
		return
	}

	Write-Host "原因：部分服務無法正常停止。" -ForegroundColor Red
	Write-Host "解法：重新啟動 Docker Desktop 後再執行停止；不要手動刪除 Volume。"
}

#endregion

#region [設定精靈]

# 方法：建立或修改客戶設定，自動產生基礎設施 Secret 並安全詢問外部憑證。
function Invoke-CustomerSetup {
	if (-not (Test-Path -LiteralPath $EnvironmentPath -PathType Leaf)) {
		if (-not (Test-Path -LiteralPath $EnvironmentExamplePath -PathType Leaf)) {
			Write-Host "原因：安裝資料夾不完整，缺少 .env.example。" -ForegroundColor Red
			Write-Host "解法：重新下載並完整解壓縮正式 Release；不要只複製 linebot.cmd。"
			$script:OperationExitCode = 7
			return
		}

		# 外部檔案 API：以範例建立第一份設定，不覆寫既有客戶設定。
		[System.IO.File]::Copy($EnvironmentExamplePath, $EnvironmentPath, $false)
	}

	$environment = Read-CustomerEnvironment $EnvironmentPath
	$companyId = Read-CustomerValue "公司代碼（小寫英文、數字、連字號）" $environment["COMPANY_ID"]
	$publicBaseUrl = Read-CustomerValue "Cloudflare 公開 HTTPS 網址" $environment["PUBLIC_BASE_URL"]
	$appPort = Read-CustomerValue "本機連接埠" $environment["APP_PORT"]
	$aiApiUrl = Read-CustomerValue "AI API 基底網址（不使用 AI 可留空）" $environment["AI_API_URL"]
	$aiModel = Read-CustomerValue "AI 模型名稱（不使用 AI 可留空）" $environment["AI_MODEL"]
	$currentTunnelValue = $environment["TUNNEL_ENABLED"]
	if ([string]::IsNullOrWhiteSpace($currentTunnelValue)) {
		$currentTunnelValue = "false"
	}
	$tunnelAnswer = Read-Host "是否由本產品啟動 Cloudflare Tunnel？目前：$currentTunnelValue（y/n，Enter 保留）"
	$tunnelEnabled = $environment["TUNNEL_ENABLED"] -eq "true"
	if ($tunnelAnswer -match "^[yY]$") { $tunnelEnabled = $true }
	if ($tunnelAnswer -match "^[nN]$") { $tunnelEnabled = $false }

	Set-CustomerEnvironmentValue $EnvironmentPath "COMPANY_ID" $companyId
	Set-CustomerEnvironmentValue $EnvironmentPath "PUBLIC_BASE_URL" $publicBaseUrl
	Set-CustomerEnvironmentValue $EnvironmentPath "APP_PORT" $appPort
	Set-CustomerEnvironmentValue $EnvironmentPath "AI_API_URL" $aiApiUrl
	Set-CustomerEnvironmentValue $EnvironmentPath "AI_MODEL" $aiModel
	Set-CustomerEnvironmentValue $EnvironmentPath "TUNNEL_ENABLED" $tunnelEnabled.ToString().ToLowerInvariant()

	if (-not [string]::IsNullOrWhiteSpace($aiApiUrl)) {
		$workflowEnabled = $environment["AI_WORKFLOW_ENABLED"] -eq "true"
		$workflowAnswer = Read-Host "啟用 harness 多模型分工？目前：$workflowEnabled（y/n，Enter 保留）"
		if ($workflowAnswer -match "^[yY]$") { $workflowEnabled = $true }
		if ($workflowAnswer -match "^[nN]$") { $workflowEnabled = $false }
		Set-CustomerEnvironmentValue $EnvironmentPath "AI_WORKFLOW_ENABLED" $workflowEnabled.ToString().ToLowerInvariant()
		if ($workflowEnabled) {
			$openAiDefaults = @{ TEXT = "gpt-5.6-luna"; VISION = "gpt-5.6-terra"; ESCALATION = "gpt-5.6-sol" }
			foreach ($role in @("TEXT", "VISION", "ESCALATION")) {
				$currentModel = $environment["AI_${role}_MODEL"]
				if ([string]::IsNullOrWhiteSpace($currentModel)) {
					$currentModel = $aiModel
					if ($aiApiUrl.TrimEnd('/') -in @("https://api.openai.com/v1", "https://api.openai.com/v1/chat/completions")) {
						$currentModel = $openAiDefaults[$role]
					}
				}
				$roleModel = Read-CustomerValue "$role 模型（需具備 API 使用權限）" $currentModel
				$roleUrl = Read-CustomerValue "$role API 網址（空白沿用基底網址）" $environment["AI_${role}_API_URL"]
				Set-CustomerEnvironmentValue $EnvironmentPath "AI_${role}_MODEL" $roleModel
				Set-CustomerEnvironmentValue $EnvironmentPath "AI_${role}_API_URL" $roleUrl
			}
		}
	}

	$environment = Read-CustomerEnvironment $EnvironmentPath
	foreach ($definition in Get-RequiredSecretDefinitions $environment) {
		$secretPath = Join-Path (Join-Path $ProjectRoot "secrets") $definition.Name
		if ((Test-Path -LiteralPath $secretPath -PathType Leaf) -and
			-not [string]::IsNullOrWhiteSpace([System.IO.File]::ReadAllText($secretPath))) {
			$replace = (Read-Host "$($definition.Label) 已設定，是否更換？（y/N）").Trim()
			if ($replace -eq "" -or $replace -match "^[nN]$") {
				Write-Host "[保留] $($definition.Label) 未更換。"
				continue
			}
			if ($replace -notmatch "^[yY]$") {
				Write-Host "[未完成] 更換選項只接受 y 或 n；本欄位尚未修改，請重新執行設定。" -ForegroundColor Red
				$script:OperationExitCode = 2
				return
			}
		}

		if ($definition.Generated) {
			$generatedValue = ""
			if (-not $definition.Empty) {
				$generatedValue = New-CustomerSecret
			}
			Save-CustomerSecret $definition.Name $generatedValue
			if ($definition.Empty) {
				Write-Host "[完成] AI 功能未啟用，不需要保存 API Key。" -ForegroundColor Green
			}
			else {
				Write-Host "[完成] 已安全產生 $($definition.Label)。" -ForegroundColor Green
			}
			continue
		}

		$secureValue = Read-Host "輸入 $($definition.Label)（畫面不會顯示內容）" -AsSecureString
		$value = ConvertFrom-CustomerSecureString $secureValue
		if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $definition.MinimumLength -or
			$value -cne $value.Trim() -or $value.Contains("`r") -or $value.Contains("`n")) {
			Write-Host "[未保存] $($definition.Label) 為空、長度不足或含多行／前後空白；原檔案保留，請重新執行設定。" -ForegroundColor Red
			$script:OperationExitCode = 2
			return
		}
		Save-CustomerSecret $definition.Name $value
		$value = $null
		$secureValue.Dispose()
		Write-Host "[完成] 已寫入並讀回確認 $($definition.Label)：$secretPath（不顯示內容）。" -ForegroundColor Green
	}

	$validation = Test-CustomerConfiguration
	Show-ConfigurationValidation $validation
	if (-not $validation.IsValid) { $script:OperationExitCode = 2; return }
	Write-Host "設定保存完成。已執行中的容器尚未自動更新；請回主選單啟動服務以套用設定。"
}

#endregion

#region [連線診斷]

# 方法：以固定格式顯示診斷階段，失敗時一定列出原因與解法。
function Write-DiagnosticResult {
	param(
		[string]$Stage,
		[bool]$Passed,
		[string]$Cause,
		[string]$Resolution
	)

	if ($Passed) {
		Write-Host "[通過] $Stage" -ForegroundColor Green
		return
	}

	Write-Host "[失敗] $Stage" -ForegroundColor Red
	Write-Host "原因：$Cause"
	Write-Host "解法：$Resolution"
}

# 方法：測試 HTTP 端點並回傳狀態，不將回應本文或憑證寫入畫面。
function Test-CustomerHttpEndpoint {
	param(
		[string]$Url,
		[hashtable]$Headers = @{}
	)

	try {
		# 外部網路 API：以短逾時送出只讀請求，定位 DNS、TLS、HTTP 或認證問題。
		$response = Invoke-WebRequest -Uri $Url -Headers $Headers -Method Get -TimeoutSec 8 -UseBasicParsing
		return [pscustomobject]@{ Passed = $response.StatusCode -ge 200 -and $response.StatusCode -lt 300; StatusCode = $response.StatusCode; ErrorType = "" }
	}
	catch {
		$statusCode = 0
		if ($null -ne $_.Exception.Response) {
			$statusCodeProperty = $_.Exception.Response.PSObject.Properties["StatusCode"]
			if ($null -ne $statusCodeProperty) {
				$statusCode = [int]$statusCodeProperty.Value
			}
		}
		return [pscustomobject]@{ Passed = $false; StatusCode = $statusCode; ErrorType = $_.Exception.GetType().Name }
	}
}

# 方法：測試指定主機與連接埠是否可建立 TCP 連線，用於區分 VPN／防火牆阻擋。
function Test-CustomerTcpEndpoint {
	param(
		[string]$HostName,
		[int]$Port,
		[int]$TimeoutMilliseconds = 5000
	)

	# 外部網路 API：建立短生命週期 TCP 用戶端，避免診斷留下常駐連線。
	$client = [System.Net.Sockets.TcpClient]::new()
	try {
		# 外部網路 API：以固定逾時測試到目標主機與連接埠的路由。
		$task = $client.ConnectAsync($HostName, $Port)
		if (-not $task.Wait($TimeoutMilliseconds)) { return $false }

		return $client.Connected
	}
	catch {
		return $false
	}
	finally {
		$client.Dispose()
	}
}

# 方法：依序測試本機、公開網域與 LINE，各階段互相獨立以精確定位故障層級。
function Invoke-CustomerDiagnosis {
	Write-Host "`n$ProductName 連線與環境診斷" -ForegroundColor Cyan
	$dockerReady = Assert-DockerReady
	Write-DiagnosticResult "Docker 引擎" $dockerReady "Docker 背景引擎無法使用。" "開啟 Docker Desktop 並等待 Engine running，再重新診斷。"
	if (-not $dockerReady) {
		$script:OperationExitCode = 3
		return
	}

	$validation = Test-CustomerConfiguration
	Write-DiagnosticResult "設定完整性" $validation.IsValid "有必要設定或 Secret 缺漏。" "執行首次設定／修改設定，依畫面逐項修正。"
	if (-not $validation.IsValid) {
		Show-ConfigurationValidation $validation
		$script:OperationExitCode = 3
		return
	}

	# 外部 Docker API：先驗證 Compose 展開結果，避免語法或路徑錯誤進入啟動流程。
	& docker compose config --quiet *> $null
	$composeValid = $LASTEXITCODE -eq 0
	Write-DiagnosticResult "Docker Compose 設定" $composeValid "Compose 無法解析目前設定或檔案路徑。" "確認專案檔案完整，且 .env 每行只有 KEY=VALUE。"
	if (-not $composeValid) {
		$script:OperationExitCode = 3
		return
	}

	$port = [int]$validation.Environment["APP_PORT"]
	$local = Test-CustomerHttpEndpoint "http://127.0.0.1:$port/readyz"
	Write-DiagnosticResult "本機服務" $local.Passed "App 尚未啟動或資料庫／物件儲存尚未 ready。" "先選擇啟動服務；若仍失敗，查看 App 紀錄中的第一個 ERROR。"

	$tunnelPassed = $true
	$edgePassed = $true
	$tunnelEnabled = $validation.Environment["TUNNEL_ENABLED"] -eq "true"
	if ($tunnelEnabled) {
		# 外部 Docker API：確認本產品自己的 Tunnel 容器正在執行，避免與另一套機器人混淆。
		$tunnelServices = @(& docker compose --profile tunnel ps --status running --services 2> $null)
		$tunnelPassed = $tunnelServices -contains "tunnel"
		Write-DiagnosticResult "Cloudflare Tunnel 容器" $tunnelPassed "本產品的 Tunnel 容器未執行或已反覆重啟。" "先確認本機服務通過，再查看 docker compose --profile tunnel logs --tail 100 tunnel；兩套機器人必須使用不同 Token 與 hostname。"

		$edgePassed = Test-CustomerTcpEndpoint "region1.v2.argotunnel.com" 7844
		Write-DiagnosticResult "Cloudflare 邊緣連線" $edgePassed "無法連到 region1.v2.argotunnel.com:7844，通常是公司 VPN、Proxy 或防火牆阻擋。" "請網管允許 TCP 7844 與 *.argotunnel.com，並保留 CLOUDFLARED_PROTOCOL=http2 後重啟 Tunnel。"
	}
	else {
		Write-Host "[略過] 內建 Cloudflare Tunnel（本產品未啟動 Connector；仍會檢查公開網域）" -ForegroundColor DarkGray
	}

	$publicUri = [System.Uri]$validation.Environment["PUBLIC_BASE_URL"]
	try {
		# 外部 DNS API：解析公開主機名稱，區分 DNS 與後續 TLS／HTTP 問題。
		$addresses = [System.Net.Dns]::GetHostAddresses($publicUri.Host)
		$dnsPassed = $addresses.Count -gt 0
	}
	catch {
		$dnsPassed = $false
	}
	Write-DiagnosticResult "公開網域 DNS" $dnsPassed "公開主機名稱無法解析。" "到 Cloudflare DNS／Tunnel Public Hostname 確認網域拼字與路由已建立。"

	$public = [pscustomobject]@{ Passed = $false; StatusCode = 0; ErrorType = "DnsUnavailable" }
	if ($dnsPassed) {
		$public = Test-CustomerHttpEndpoint "$($publicUri.Scheme)://$($publicUri.Authority)/livez"
		$publicCause = "公開網址無法以 HTTPS 連到 App；錯誤類型：$($public.ErrorType)。"
		$publicResolution = "確認 Cloudflare Tunnel 為 Healthy，服務 URL 指向 http://app:8088；公司 VPN 環境優先使用 HTTP/2。"
		if ($public.StatusCode -eq 404) {
			$publicCause = "公開網址已連上服務，但 /livez 回傳 HTTP 404，通常是 hostname 路由到錯誤服務。"
			$publicResolution = "在 Cloudflare Tunnel Public Hostname 將服務 URL 改為 http://app:8088，再重新診斷。"
		}
		elseif ($public.StatusCode -eq 502) {
			$publicCause = "Cloudflare 已收到請求，但無法連到 App，回傳 HTTP 502。"
			$publicResolution = "先確認本機服務通過，再確認 Tunnel 與 App 位於同一 Compose project，服務 URL 為 http://app:8088。"
		}
		elseif ($public.StatusCode -eq 403) {
			$publicCause = "公開網址被 Cloudflare Access 或防火牆拒絕，回傳 HTTP 403。"
			$publicResolution = "Webhook hostname 不可要求互動式登入；請在 Cloudflare Access 建立適用於 LINE Webhook 的服務規則。"
		}
		Write-DiagnosticResult "公開網域 HTTPS" $public.Passed $publicCause $publicResolution
	}
	else {
		Write-Host "[略過] 公開網域 HTTPS（請先修正上一階段的 DNS）" -ForegroundColor DarkGray
	}

	$lineTokenPath = Join-Path (Join-Path $ProjectRoot "secrets") "line-channel-token"
	# 外部檔案 API：只將 Token 用於 LINE 官方驗證請求，不輸出或保存到 Log。
	$lineToken = [System.IO.File]::ReadAllText($lineTokenPath).Trim()
	$line = Test-CustomerHttpEndpoint "https://api.line.me/v2/bot/info" @{ Authorization = "Bearer $lineToken" }
	$lineCause = "無法連到 LINE API；可能是 VPN、Proxy、DNS 或 TLS 攔截。"
	$lineResolution = "暫停 VPN 後重測；若恢復，請網管允許 api.line.me:443 且不要進行 TLS 解密。"
	if ($line.StatusCode -eq 401) {
		$lineCause = "LINE Token 無效、已撤銷或貼錯 Channel。"
		$lineResolution = "到 LINE Developers 重新發行正確 Channel 的 Access Token，再執行修改設定。"
	}
	elseif ($line.StatusCode -eq 403) {
		$lineCause = "LINE 已辨識 Token，但目前 Channel 或帳號沒有執行權限。"
		$lineResolution = "確認 Token 來自 Messaging API Channel，並檢查 Provider／管理員權限後重新發行。"
	}
	elseif ($line.StatusCode -eq 429) {
		$lineCause = "LINE API 暫時限制請求，回傳 HTTP 429。"
		$lineResolution = "等待一分鐘後重試；若持續發生，停止重複診斷並檢查 LINE 用量與其他呼叫來源。"
	}
	Write-DiagnosticResult "LINE API" $line.Passed $lineCause $lineResolution

	if ($local.Passed -and $tunnelPassed -and $edgePassed -and $dnsPassed -and $public.Passed -and $line.Passed) {
		Write-Host "`n[完成] 本機、公開網域與 LINE API 均正常。最後請到 LINE Developers 對 Webhook URL 執行 Verify。" -ForegroundColor Green
		return
	}

	Write-Host "`n診斷未全部通過；請從第一個失敗階段開始處理，後續錯誤通常是連鎖結果。" -ForegroundColor Yellow
	$script:OperationExitCode = 3
}

#endregion

#region [主選單]

# 方法：顯示非技術使用者可理解的操作選單。
function Show-CustomerMenu {
	do {
		Clear-Host
		Write-Host "$ProductName 客戶控制台" -ForegroundColor Cyan
		Write-Host "1. 首次設定／修改設定"
		Write-Host "2. 啟動服務"
		Write-Host "3. 查看服務狀態"
		Write-Host "4. 連線與環境診斷"
		Write-Host "5. 查看 App 紀錄"
		Write-Host "6. 停止服務（保留資料）"
		Write-Host "0. 離開"
		$selection = Read-Host "請輸入選項"

		switch ($selection) {
			"1" { Invoke-CustomerSetup }
			"2" { Start-CustomerService }
			"3" { Show-CustomerStatus }
			"4" { Invoke-CustomerDiagnosis }
			"5" { Show-CustomerLogs }
			"6" { Stop-CustomerService }
			"0" { return }
			default { Write-Host "無效選項，請輸入 0–6。" -ForegroundColor Yellow }
		}

		if ($selection -ne "0") {
			Read-Host "`n按 Enter 回到主選單" | Out-Null
		}
	}
	while ($true)
}

# 方法：切換到專案根目錄並執行指定客戶操作，結束後恢復原工作目錄。
function Invoke-CustomerAction {
	# 外部檔案 API：切換至 Compose 所在目錄，避免從捷徑啟動時讀錯專案。
	Push-Location $ProjectRoot
	try {
		switch ($Action) {
			"Menu" { Show-CustomerMenu }
			"Setup" { Invoke-CustomerSetup }
			"Validate" {
				$validation = Test-CustomerConfiguration
				Show-ConfigurationValidation $validation
				if (-not $validation.IsValid) { $script:OperationExitCode = 2 }
			}
			"Start" { Start-CustomerService }
			"Status" { Show-CustomerStatus }
			"Diagnose" { Invoke-CustomerDiagnosis }
			"Logs" { Show-CustomerLogs }
			"Stop" { Stop-CustomerService }
		}
	}
	catch {
		Write-Host "`n[意外錯誤] 操作未完成，現有設定與資料不會主動刪除。" -ForegroundColor Red
		Write-Host "原因類型：$($_.Exception.GetType().Name)"
		Write-Host "解法：確認資料夾可讀寫且未被防毒軟體隔離，再重新執行；若仍失敗，請將此原因類型與發生時間提供給維護人員，不要提供 Secret。"
		$script:OperationExitCode = 10
	}
	finally {
		# 外部檔案 API：恢復呼叫控制台前的原始工作目錄。
		Pop-Location
	}
}

# 外部主流程：執行客戶選擇的操作。
Invoke-CustomerAction
exit $OperationExitCode

#endregion
