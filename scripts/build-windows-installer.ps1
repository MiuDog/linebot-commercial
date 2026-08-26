param(
	[Parameter(Mandatory = $true)]
	[string]$Version,
	[string]$NsisInstallerPath = "",
	[switch]$SkipAppPackage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$TargetRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "target\nsis-toolchain"))
$NsisRoot = [System.IO.Path]::GetFullPath((Join-Path $TargetRoot "nsis"))
$MakensisPath = Join-Path $NsisRoot "makensis.exe"
$AppImage = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "dist\app-image\LinebotCommercial"))
$InstallerScript = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "packaging\windows\installer.nsi"))
$AppIconPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "packaging\windows\app-icon.ico"))
$LicensePath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "packaging\windows\license.rtf"))
$ReleaseMetadataPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "packaging\windows\release.properties"))
$ThirdPartyNoticesPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "docs\third-party-notices.md"))
$SbomPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "target\classes\META-INF\sbom\sbom.json"))
$OutputPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "dist\LinebotCommercial-Setup-$Version.exe"))
$NsisVersion = "3.12"
$NsisZipSha256 = "56581F90DB321581C5381193D796FFFCF2D24B2F8FED2160A6C6A3BAA67F2C4F"
$NsisPackageUrl = "https://community.chocolatey.org/api/v2/package/nsis.portable/3.12.0"

#endregion

#region [方法]

# 方法：驗證版本與 Maven 專案版本完全一致。
function Assert-Version {
	param(
		[string]$Value
	)

	if ($Value -notmatch '^\d+\.\d+\.\d+$') {
		throw "Version 必須是 0.1.0 格式。"
	}

	[xml]$Pom = Get-Content -LiteralPath (Join-Path $ProjectRoot "pom.xml") -Raw -Encoding UTF8

	if ([string]$Pom.project.version -ne $Value) {
		throw "Maven 版本與 Setup 版本不一致。"
	}
}

# 方法：以官方 SHA-256 驗證 NSIS zip，拒絕未知建置工具。
function Assert-NsisZipHash {
	param(
		[string]$ZipPath
	)

	$ActualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ZipPath).Hash

	if ($ActualHash -ne $NsisZipSha256) {
		throw "NSIS zip SHA-256 驗證失敗。"
	}
}

# 方法：從已審核套件取得官方 NSIS zip 並驗證固定 SHA-256。
function Get-VerifiedNsisZip {
	if ($NsisInstallerPath -ne "") {
		$ResolvedZip = [System.IO.Path]::GetFullPath($NsisInstallerPath)
		Assert-NsisZipHash -ZipPath $ResolvedZip

		return $ResolvedZip
	}

	$PackagePath = Join-Path $TargetRoot "nsis.portable.$NsisVersion.nupkg"
	$ArchivePath = Join-Path $TargetRoot "nsis.portable.$NsisVersion.zip"
	$ExtractPath = Join-Path $TargetRoot "package"

	New-Item -ItemType Directory -Path $TargetRoot -Force | Out-Null

	# 外部函式：下載含官方 binary 的已審核 NSIS portable 套件，後續仍以官方 SHA-256 驗證。
	Invoke-WebRequest -Uri $NsisPackageUrl -OutFile $PackagePath
	Copy-Item -LiteralPath $PackagePath -Destination $ArchivePath -Force
	Expand-Archive -LiteralPath $ArchivePath -DestinationPath $ExtractPath -Force
	$ResolvedZip = Get-ChildItem -LiteralPath $ExtractPath -Recurse -Filter "nsis-$NsisVersion.zip" |
		Select-Object -First 1 -ExpandProperty FullName

	if ($null -eq $ResolvedZip) {
		throw "已下載套件中找不到 NSIS zip。"
	}

	Assert-NsisZipHash -ZipPath $ResolvedZip

	return $ResolvedZip
}

# 方法：準備專案內固定版本 NSIS 工具鏈，不修改全域系統安裝。
function Initialize-NsisToolchain {
	if (Test-Path -LiteralPath $MakensisPath -PathType Leaf) {
		return
	}

	$Zip = Get-VerifiedNsisZip
	New-Item -ItemType Directory -Path $NsisRoot -Force | Out-Null
	$ExtractRoot = Join-Path $TargetRoot "nsis-extracted"
	Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force
	Copy-Item -Path (Join-Path $ExtractRoot "nsis-$NsisVersion\*") -Destination $NsisRoot -Recurse -Force

	if (-not (Test-Path -LiteralPath $MakensisPath -PathType Leaf)) {
		throw "NSIS $NsisVersion 工具鏈準備失敗。"
	}
}

#endregion

#region [主流程]

# 步驟一：驗證版本並建立最新自包含 app image。
Assert-Version -Value $Version

if (-not $SkipAppPackage) {
	# 外部函式：沿用單一 app image 封裝入口，避免 installer 與 launcher 設定分歧。
	& (Join-Path $PSScriptRoot "package-windows-app.ps1") -Version $Version

	if ($LASTEXITCODE -ne 0) {
		throw "Windows app image 建置失敗。"
	}
}

if (-not (Test-Path -LiteralPath $AppImage -PathType Container)) {
	throw "找不到 app image：$AppImage"
}

if (-not (Test-Path -LiteralPath $AppIconPath -PathType Leaf)) {
	throw "找不到 commercial Windows 圖示：$AppIconPath"
}

if (-not (Test-Path -LiteralPath $LicensePath -PathType Leaf)) {
	throw "找不到安裝授權文件：$LicensePath"
}

if (-not (Test-Path -LiteralPath $ReleaseMetadataPath -PathType Leaf)) {
	throw "找不到 Release metadata：$ReleaseMetadataPath"
}

if (-not (Test-Path -LiteralPath $ThirdPartyNoticesPath -PathType Leaf)) {
	throw "找不到第三方授權聲明：$ThirdPartyNoticesPath"
}

if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
	throw "找不到 SBOM：$SbomPath，請先執行 Maven verify。"
}

# 步驟二：準備鎖定版本的 NSIS 並編譯單一 Setup.exe。
Initialize-NsisToolchain
$ReleaseMetadata = Get-Content -LiteralPath $ReleaseMetadataPath -Raw -Encoding utf8 | ConvertFrom-StringData

if ([string]::IsNullOrWhiteSpace($ReleaseMetadata.publisher) -or [string]::IsNullOrWhiteSpace($ReleaseMetadata.supportUrl)) {
	throw "Publisher 與 supportUrl 不可為空白。"
}

if (Test-Path -LiteralPath $OutputPath) {
	Remove-Item -LiteralPath $OutputPath -Force
}

$MakensisArguments = @(
	"/V3",
	"/DAPP_VERSION=$Version",
	"/DAPP_IMAGE=$AppImage",
	"/DAPP_ICON=$AppIconPath",
	"/DOUTPUT_FILE=$OutputPath",
	"/DTHIRD_PARTY_NOTICES=$ThirdPartyNoticesPath",
	"/DSBOM_FILE=$SbomPath",
	"/DPUBLISHER=$($ReleaseMetadata.publisher)",
	"/DSUPPORT_URL=$($ReleaseMetadata.supportUrl)",
	$InstallerScript
)

# 外部函式：以獨立 define 參數編譯 NSIS script 並檢查 exit code。
& $MakensisPath @MakensisArguments

if ($LASTEXITCODE -ne 0) {
	throw "NSIS installer 編譯失敗，exit code：$LASTEXITCODE"
}

if (-not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
	throw "找不到預期 Setup.exe：$OutputPath"
}

Write-Information "Windows Setup 已建立：$OutputPath" -InformationAction Continue

#endregion
