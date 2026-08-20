Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$WorkflowRoot = Join-Path $ProjectRoot ".github\workflows"
$WorkflowPaths = @(
	(Join-Path $WorkflowRoot "ci.yml"),
	(Join-Path $WorkflowRoot "release-windows.yml")
)

#endregion

#region [主流程]

# 步驟一：確認 workflow 存在、沒有 Tab，且所有 uses 都鎖定完整 commit SHA。
foreach ($WorkflowPath in $WorkflowPaths) {
	if (-not (Test-Path -LiteralPath $WorkflowPath -PathType Leaf)) {
		throw "找不到 workflow：$WorkflowPath"
	}

	$Content = Get-Content -LiteralPath $WorkflowPath -Raw -Encoding utf8

	if ($Content.Contains("`t")) {
		throw "YAML 不可含 Tab：$WorkflowPath"
	}

	foreach ($Match in ([regex]::Matches($Content, 'uses:\s*([^\s#]+)'))) {
		if ($Match.Groups[1].Value -notmatch '@[0-9a-f]{40}$') {
			throw "Action 未鎖定完整 commit SHA：$($Match.Groups[1].Value)"
		}
	}
}

# 步驟二：確認 CI 最小權限與 Release 的受保護環境、簽章及單一資產命令存在。
$Ci = Get-Content -LiteralPath $WorkflowPaths[0] -Raw -Encoding utf8
$Release = Get-Content -LiteralPath $WorkflowPaths[1] -Raw -Encoding utf8

if ($Ci -notmatch 'permissions:\s+contents: read' -or $Ci -match 'contents: write') {
	throw "CI workflow 權限不是唯讀。"
}

foreach ($RequiredText in @("commercial-release", "sign-windows-artifacts.ps1", "verify-release.ps1", "gh release create")) {
	if (-not $Release.Contains($RequiredText)) {
		throw "Release workflow 缺少必要 gate：$RequiredText"
	}
}

Write-Information "Workflow 靜態安全檢查通過。" -InformationAction Continue

#endregion

