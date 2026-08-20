param(
	[Parameter(Mandatory = $true)]
	[string]$AppImagePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$TargetRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "target"))
$ResolvedAppImage = [System.IO.Path]::GetFullPath($AppImagePath)
$LauncherPath = Join-Path $ResolvedAppImage "LinebotCommercial.exe"
$RuntimeJavaLibrary = Join-Path $ResolvedAppImage "runtime\bin\java.dll"
$RuntimeJvmLibrary = Join-Path $ResolvedAppImage "runtime\bin\server\jvm.dll"
$SmokeLocalAppData = [System.IO.Path]::GetFullPath((Join-Path $TargetRoot "app-image-smoke-localappdata"))
$EvidenceRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "dist\app-image-evidence"))
$EvidencePath = Join-Path $EvidenceRoot "self-contained-runtime.json"
$OriginalLocalAppData = $env:LOCALAPPDATA
$OriginalPath = $env:PATH
$OriginalJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
$OriginalJdkHome = [Environment]::GetEnvironmentVariable("JDK_HOME", "Process")
$Process = $null

#endregion

#region [方法]

# 方法：驗證測試資料目錄嚴格位於專案 target 內，避免清理其他使用者資料。
function Assert-SmokePath {
	$ExpectedPrefix = $TargetRoot.TrimEnd('\') + '\'

	if (-not $SmokeLocalAppData.StartsWith($ExpectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
		throw "App image smoke 目錄超出 target：$SmokeLocalAppData"
	}
}

# 方法：還原目前 PowerShell process 的 Java 與 LocalAppData 環境。
function Restore-ProcessEnvironment {
	$env:LOCALAPPDATA = $OriginalLocalAppData
	$env:PATH = $OriginalPath

	if ($null -eq $OriginalJavaHome) {
		Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
	}
	else {
		$env:JAVA_HOME = $OriginalJavaHome
	}

	if ($null -eq $OriginalJdkHome) {
		Remove-Item Env:JDK_HOME -ErrorAction SilentlyContinue
	}
	else {
		$env:JDK_HOME = $OriginalJdkHome
	}
}

#endregion

#region [主流程]

# 步驟一：驗證 launcher 與 app image 內兩個必要 Runtime library。
foreach ($RequiredPath in @($LauncherPath, $RuntimeJavaLibrary, $RuntimeJvmLibrary)) {
	if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
		throw "App image 缺少必要檔案：$RequiredPath"
	}
}

Assert-SmokePath

try {
	New-Item -ItemType Directory -Path $SmokeLocalAppData -Force | Out-Null
	$env:LOCALAPPDATA = $SmokeLocalAppData
	$env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
	Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
	Remove-Item Env:JDK_HOME -ErrorAction SilentlyContinue

	# 步驟二：在沒有系統 Java 環境的 child process 執行 desktop shutdown，禁止啟動 Spring server。
	$Process = Start-Process -FilePath $LauncherPath -ArgumentList @("--shutdown") -PassThru -WindowStyle Hidden
	$Completed = $Process.WaitForExit(30000)

	if (-not $Completed) {
		$Process.Kill()
		throw "App image 自帶 Runtime smoke test 逾時，desktop launcher 可能落入 server mode。"
	}

	if ($Process.ExitCode -ne 0) {
		throw "App image 自帶 Runtime smoke test 失敗：$($Process.ExitCode)"
	}

	# 步驟三：launcher 會另外啟動 JVM child process，父程序結束不代表 App 已停止；
	# 必須確認 app image 沒有留下背景程序，否則升級與解除安裝會被鎖住的檔案擋下。
	$ResidualDeadline = (Get-Date).AddSeconds(10)
	$Residual = @()

	do {
		$Residual = @(Get-Process -ErrorAction SilentlyContinue |
			Where-Object { $_.Path -and $_.Path.StartsWith($ResolvedAppImage, [StringComparison]::OrdinalIgnoreCase) })

		if ($Residual.Count -eq 0) { break }

		Start-Sleep -Milliseconds 500
	} while ((Get-Date) -lt $ResidualDeadline)

	if ($Residual.Count -gt 0) {
		throw "desktop shutdown 後仍有 $($Residual.Count) 個 app image 程序存活：$($Residual.Id -join ',')"
	}

	# 步驟四：保存不含機密的自帶 Runtime 驗收證據。
	$Evidence = [ordered]@{
		appImage = $ResolvedAppImage
		exitCode = $Process.ExitCode
		residualProcesses = 0
		javaHomeRemoved = $true
		jdkHomeRemoved = $true
		pathContainsJava = $false
		runtimeJavaDll = $true
		runtimeJvmDll = $true
	}
	New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
	$Evidence | ConvertTo-Json | Set-Content -LiteralPath $EvidencePath -Encoding utf8
	Write-Information "App image 驗收證據：$EvidencePath" -InformationAction Continue
}
finally {
	Restore-ProcessEnvironment

	if ($null -ne $Process -and -not $Process.HasExited) {
		$Process.Kill()
	}

	Remove-Item -LiteralPath $SmokeLocalAppData -Recurse -Force -ErrorAction SilentlyContinue
}

#endregion

