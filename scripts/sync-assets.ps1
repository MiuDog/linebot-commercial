param(
	[switch]$Watch,
	[switch]$Background,
	[int]$IntervalSeconds = 30
)

$ErrorActionPreference = "Stop"

# 驗證：背景循環的間隔至少為一秒，避免錯誤設定造成忙碌迴圈。
if ($IntervalSeconds -lt 1) {
	throw "IntervalSeconds 必須大於或等於 1。"
}

# 流程：需要背景執行時，以隱藏 PowerShell 視窗重新啟動本腳本。
if ($Background) {
	$arguments = @(
		"-NoProfile",
		"-ExecutionPolicy",
		"Bypass",
		"-File",
		"`"$PSCommandPath`"",
		"-Watch",
		"-IntervalSeconds",
		$IntervalSeconds
	)

	# 外部 API：啟動不顯示視窗的獨立 PowerShell 同步程序。
	$process = Start-Process `
		-FilePath "powershell.exe" `
		-ArgumentList $arguments `
		-WindowStyle Hidden `
		-PassThru
	Write-Output "圖片資料庫同步已在背景啟動，PID=$($process.Id)"
	return
}

# 方法：透過容器內 localhost 與環境權杖執行一次同步。
function Invoke-AssetSync {
	# 相容性：尾端註解會吸收 Windows PowerShell 管線附加的 CR 字元，避免污染 URL。
	$command = 'if [ -z "$ASSETS_SYNC_TOKEN" ]; then echo "尚未設定 ASSETS_SYNC_TOKEN" >&2; exit 2; fi; exec curl --fail --silent --show-error -X POST -H "X-Sync-Token: $ASSETS_SYNC_TOKEN" http://localhost:8088/internal/storage/synchronize #'

	# 外部 API：由標準輸入傳入 Shell 指令，避免 PowerShell 改寫多層引號。
	$command | docker compose exec -T linebot sh
	if ($LASTEXITCODE -ne 0) {
		throw "圖片資料庫同步失敗，結束代碼：$LASTEXITCODE"
	}
}

# 流程：切換到專案根目錄，確保 docker compose 能找到設定檔。
$projectRoot = Split-Path -Parent $PSScriptRoot

# 外部 API：暫時切換工作目錄，完成後一定恢復原位置。
Push-Location $projectRoot
try {
	# 流程：預設只執行一次；Watch 模式則依指定秒數持續同步。
	do {
		Invoke-AssetSync
		if ($Watch) {
			# 外部 API：等待下一次背景同步時間。
			Start-Sleep -Seconds $IntervalSeconds
		}
	}
	while ($Watch)
}
finally {
	# 外部 API：恢復執行腳本前的工作目錄。
	Pop-Location
}
