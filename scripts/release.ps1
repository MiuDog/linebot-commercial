param(
	[Parameter(Mandatory = $true)]
	[string]$Version,
	[string]$Branch = "main",
	[switch]$SkipVerify,
	[switch]$Force,
	[switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$PomPath = Join-Path $ProjectRoot "pom.xml"
$ReadmePath = Join-Path $ProjectRoot "README.md"
$MavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"
$Tag = "v$Version"

#endregion

#region [方法]

# 方法：輸出流程步驟；DryRun 時同時標示不會實際執行。
function Write-Step {
	param(
		[string]$Message
	)

	$Prefix = if ($DryRun) { "[DryRun] " } else { "" }

	Write-Information "$Prefix$Message" -InformationAction Continue
}

# 方法：執行外部指令並在非零 exit code 時中止，避免半完成的發版狀態。
function Invoke-Tool {
	param(
		[string]$Command,
		[string[]]$Arguments
	)

	if ($DryRun) {
		Write-Step "會執行：$Command $($Arguments -join ' ')"

		return
	}

	& $Command @Arguments | Out-Host

	if ($LASTEXITCODE -ne 0) {
		throw "外部工具執行失敗：$Command $($Arguments -join ' ')，exit code：$LASTEXITCODE"
	}
}

# 方法：取得指定 git 指令的輸出，供狀態判斷使用。
function Get-GitValue {
	param(
		[string[]]$Arguments
	)

	# 外部函式：讀取 git 狀態；此處不改變版本庫，因此 DryRun 也要真實查詢。
	# 不重導 stderr：Windows PowerShell 5.1 會把原生指令的 stderr 包成 ErrorRecord，
	# 在 $ErrorActionPreference = "Stop" 下即使 exit code 為 0 也會誤判成失敗。
	$Output = & git @Arguments

	if ($LASTEXITCODE -ne 0) {
		throw "git $($Arguments -join ' ') 失敗，exit code：$LASTEXITCODE"
	}

	return ($Output | Out-String).Trim()
}

# 方法：以無 BOM UTF-8 覆寫文字檔，維持既有檔案的位元組表示法。
function Write-TextFile {
	param(
		[string]$Path,
		[string]$Content
	)

	# 外部函式：Set-Content -Encoding UTF8 在 5.1 會加上 BOM，因此改用 .NET 明確指定不加。
	[System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
}

# 方法：確認版本格式，並拒絕與既有 tag 衝突而未明確要求覆蓋的情況。
function Assert-VersionAvailable {
	if ($Version -notmatch '^\d+\.\d+\.\d+$') {
		throw "Version 必須是 0.1.0 這種三段格式。"
	}

	# 外部函式：查詢遠端是否已存在同名 tag，避免覆蓋已散布的版本。
	$Existing = Get-GitValue -Arguments @("ls-remote", "--tags", "origin", "refs/tags/$Tag")

	if ($Existing -eq "") { return }

	if (-not $Force) {
		throw "遠端已存在 tag $Tag。確定要重新指向請加上 -Force，或改用新的版本號。"
	}

	Write-Step "遠端已存在 $Tag，將依 -Force 重新指向。"
}

# 方法：確認工作目錄乾淨且與遠端同步，發版才不會夾帶未預期的變更。
function Assert-CleanRepository {
	$Status = Get-GitValue -Arguments @("status", "--porcelain")

	if ($Status -ne "") {
		throw "工作目錄有未提交的變更，請先處理：`n$Status"
	}

	$Current = Get-GitValue -Arguments @("rev-parse", "--abbrev-ref", "HEAD")

	if ($Current -ne $Branch) {
		throw "目前在 $Current，發版必須在 $Branch 進行。"
	}

	# 外部函式：更新遠端參照，確保後續比對的是最新狀態。
	Invoke-Tool -Command "git" -Arguments @("fetch", "--quiet", "origin", $Branch)

	if ($DryRun) { return }

	$Local = Get-GitValue -Arguments @("rev-parse", "HEAD")
	$Remote = Get-GitValue -Arguments @("rev-parse", "origin/$Branch")

	if ($Local -ne $Remote) {
		throw "$Branch 與 origin/$Branch 不同步，請先 pull 或 push。"
	}
}

# 方法：只改寫專案自身的版本，不影響 parent 的 Spring Boot 版本。
function Set-ProjectVersion {
	[xml]$Pom = Get-Content -LiteralPath $PomPath -Raw -Encoding UTF8
	$Current = [string]$Pom.project.version

	if ($Current -eq $Version) {
		Write-Step "pom.xml 已經是 $Version，略過改寫。"

		return $false
	}

	Write-Step "pom.xml 版本 $Current 改為 $Version。"

	if ($DryRun) { return $true }

	# 演算法步驟：以專案 artifactId 定位，確保只取代 project 層級的版本節點。
	$Content = Get-Content -LiteralPath $PomPath -Raw -Encoding UTF8
	$Pattern = '(<artifactId>linebot-commercial</artifactId>\s*\r?\n\s*<version>)[^<]+(</version>)'
	$Updated = [regex]::Replace($Content, $Pattern, "`${1}$Version`${2}", 1)

	if ($Updated -eq $Content) {
		throw "找不到可改寫的專案版本節點，請確認 pom.xml 結構。"
	}

	Write-TextFile -Path $PomPath -Content $Updated

	return $true
}

# 方法：同步 README 開頭的版本標記，讓文件與實際發版一致。
function Set-ReadmeVersion {
	$Content = Get-Content -LiteralPath $ReadmePath -Raw -Encoding UTF8
	$Pattern = '(`@linebot-commercial@)\d+\.\d+\.\d+(`)'
	$Updated = [regex]::Replace($Content, $Pattern, "`${1}$Version`${2}", 1)

	if ($Updated -eq $Content) { return $false }

	Write-Step "README 版本標記改為 $Version。"

	if ($DryRun) { return $true }

	Write-TextFile -Path $ReadmePath -Content $Updated

	return $true
}

#endregion

#region [流程]

# 步驟一：發版前置檢查，任何一項不符就在改動版本庫之前中止。
Write-Step "準備發佈 $Tag。"
Assert-VersionAvailable
Assert-CleanRepository

# 步驟二：改寫版本並提交；版本已正確時不建立空提交。
$PomChanged = Set-ProjectVersion
$ReadmeChanged = Set-ReadmeVersion

if ($PomChanged -or $ReadmeChanged) {
	Invoke-Tool -Command "git" -Arguments @("add", "pom.xml", "README.md")
	Invoke-Tool -Command "git" -Arguments @("commit", "-m", "chore(release): 版本升至 $Version")
}
else {
	Write-Step "版本字串皆已是 $Version，沒有需要提交的變更。"
}

# 步驟三：本機完整驗證，避免把註定失敗的 tag 推上 GitHub 佔用 runner 時間。
if ($SkipVerify) {
	Write-Step "已指定 -SkipVerify，略過本機 mvnw clean verify。"
}
else {
	Write-Step "執行本機完整驗證。"
	Invoke-Tool -Command $MavenWrapper -Arguments @("--batch-mode", "--no-transfer-progress", "clean", "verify")
}

# 步驟四：先推分支再推 tag，確保 tag 指向的 commit 在遠端已存在。
Invoke-Tool -Command "git" -Arguments @("push", "origin", $Branch)

if ($Force) {
	# 外部函式：重新指向既有 tag 前先刪除遠端舊 ref，避免 push 被拒。
	Invoke-Tool -Command "git" -Arguments @("push", "origin", "--delete", $Tag)
}

Invoke-Tool -Command "git" -Arguments @("tag", "-f", "-a", $Tag, "-m", "Release $Tag")
Invoke-Tool -Command "git" -Arguments @("push", "origin", $Tag)

Write-Step "已推送 $Tag，Windows Release workflow 會自動建置並建立 GitHub Release。"
Write-Step "查看進度：gh run list --workflow 'Windows Release' --limit 3"

#endregion
