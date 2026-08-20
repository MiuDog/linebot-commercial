param(
	[Parameter(Mandatory = $true)]
	[string]$Version,
	[switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$TargetRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "target\windows-package"))
$InputRoot = [System.IO.Path]::GetFullPath((Join-Path $TargetRoot "input"))
$DistRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "dist"))
$ImageRoot = [System.IO.Path]::GetFullPath((Join-Path $DistRoot "app-image"))
$JarPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "target\app.jar"))
$LauncherPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "packaging\windows\launcher.properties"))
$ProductName = "LinebotCommercial"

#endregion

#region [方法]

# 方法：驗證版本只能使用 Maven 與 jpackage 都接受的數字點分格式。
function Assert-Version {
	param(
		[string]$Value
	)

	if ($Value -notmatch '^\d+\.\d+\.\d+(?:\.\d+)?$') {
		throw "Version 必須是 0.1.0 或 0.1.0.0 格式。"
	}
}

# 方法：驗證可能清理的路徑確實位於指定產品建置根目錄內。
function Assert-ChildPath {
	param(
		[string]$Candidate,
		[string]$ExpectedParent
	)

	$ResolvedCandidate = [System.IO.Path]::GetFullPath($Candidate)
	$ResolvedParent = [System.IO.Path]::GetFullPath($ExpectedParent).TrimEnd('\') + '\'

	if (-not $ResolvedCandidate.StartsWith($ResolvedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
		throw "拒絕操作產品建置目錄外的路徑：$ResolvedCandidate"
	}
}

# 方法：執行外部工具並在非零 exit code 時立即停止封裝。
function Invoke-CheckedCommand {
	param(
		[string]$Command,
		[string[]]$Arguments
	)

	# 外部函式：以獨立參數呼叫 Maven Wrapper 或 jpackage，避免字串命令注入。
	& $Command @Arguments

	if ($LASTEXITCODE -ne 0) {
		throw "外部工具執行失敗：$Command，exit code：$LASTEXITCODE"
	}
}

# 方法：讀取 launcher properties 的必要設定值。
function Get-LauncherSetting {
	param(
		[string]$Name
	)

	$MatchedLine = Get-Content -LiteralPath $LauncherPath -Encoding UTF8 |
		Where-Object { $_ -match "^$([regex]::Escape($Name))=(.*)$" } |
		Select-Object -First 1

	if ($null -eq $MatchedLine) {
		throw "launcher.properties 缺少必要設定：$Name"
	}

	return $MatchedLine.Substring($Name.Length + 1)
}

#endregion

#region [主流程]

# 步驟一：驗證版本、專案版本、必要檔案與外部工具。
Assert-Version -Value $Version
[xml]$Pom = Get-Content -LiteralPath (Join-Path $ProjectRoot "pom.xml") -Raw -Encoding UTF8
$PomVersion = [string]$Pom.project.version

if ($PomVersion -ne $Version) {
	throw "Maven 版本 $PomVersion 與封裝版本 $Version 不一致。"
}

if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
	throw "找不到 desktop launcher 設定：$LauncherPath"
}

$JpackageCommand = (Get-Command "jpackage.exe" -ErrorAction Stop).Source
$MavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"

# 步驟二：執行完整 Maven 驗證並建立固定名稱 app.jar。
if (-not $SkipBuild) {
	Invoke-CheckedCommand -Command $MavenWrapper -Arguments @("clean", "verify")
}

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
	throw "找不到 Maven 產物：$JarPath"
}

# 步驟三：只清理已驗證的建置輸出，並建立 jpackage 單一輸入目錄。
Assert-ChildPath -Candidate $TargetRoot -ExpectedParent $ProjectRoot
Assert-ChildPath -Candidate $ImageRoot -ExpectedParent $ProjectRoot

if (Test-Path -LiteralPath $TargetRoot) {
	Remove-Item -LiteralPath $TargetRoot -Recurse -Force
}

if (Test-Path -LiteralPath $ImageRoot) {
	Remove-Item -LiteralPath $ImageRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $InputRoot -Force | Out-Null
New-Item -ItemType Directory -Path $ImageRoot -Force | Out-Null
Copy-Item -LiteralPath $JarPath -Destination (Join-Path $InputRoot "app.jar")

# 步驟四：讀取桌面專用參數並建立包含 Java Runtime 的 app image。
$LauncherArguments = Get-LauncherSetting -Name "arguments"
$JavaOptions = Get-LauncherSetting -Name "java-options"
$DesktopJavaOption = Get-LauncherSetting -Name "desktop-java-option"
$JpackageArguments = @(
	"--type", "app-image",
	"--name", $ProductName,
	"--app-version", $Version,
	"--vendor", "MiuDog",
	"--description", "LINE 群組資產管理與報價桌面應用程式",
	"--input", $InputRoot,
	"--main-jar", "app.jar",
	"--dest", $ImageRoot,
	"--arguments", $LauncherArguments,
	"--java-options", $JavaOptions,
	"--java-options", $DesktopJavaOption
)
Invoke-CheckedCommand -Command $JpackageCommand -Arguments $JpackageArguments

$LauncherExe = Join-Path $ImageRoot "$ProductName\$ProductName.exe"
$BundledJvm = Join-Path $ImageRoot "$ProductName\runtime\bin\server\jvm.dll"
$BundledJavaLibrary = Join-Path $ImageRoot "$ProductName\runtime\bin\java.dll"

if (-not (Test-Path -LiteralPath $LauncherExe -PathType Leaf)) {
	throw "jpackage 未產生預期 launcher：$LauncherExe"
}

if (-not (Test-Path -LiteralPath $BundledJvm -PathType Leaf)) {
	throw "app image 未包含 JVM Runtime：$BundledJvm"
}

if (-not (Test-Path -LiteralPath $BundledJavaLibrary -PathType Leaf)) {
	throw "app image 未包含 Java Runtime library：$BundledJavaLibrary"
}

Write-Information "Windows app image 已建立：$ImageRoot\$ProductName" -InformationAction Continue

#endregion
