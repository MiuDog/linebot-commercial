param(
	[Parameter(Mandatory = $true)]
	[string]$Version,
	[Parameter(Mandatory = $true)]
	[string]$InstallerPath,
	[string]$Tag = "",
	[switch]$RequireSignature,
	[switch]$RequireCommercialMetadata
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$ResolvedInstaller = [System.IO.Path]::GetFullPath($InstallerPath)
$ReleaseMetadataPath = Join-Path $ProjectRoot "packaging\windows\release.properties"
$LicensePath = Join-Path $ProjectRoot "packaging\windows\license.rtf"
$SbomPath = Join-Path $ProjectRoot "target\classes\META-INF\sbom\sbom.json"
$NoticesPath = Join-Path $ProjectRoot "docs\third-party-notices.md"

#endregion

#region [方法]

# 方法：讀取 Java properties 格式的商用發佈欄位。
function Read-ReleaseMetadata {
	$Metadata = @{}

	foreach ($Line in Get-Content -LiteralPath $ReleaseMetadataPath -Encoding utf8) {
		if ($Line.Trim() -eq "" -or $Line.TrimStart().StartsWith("#")) {
			continue

		}

		$Parts = $Line.Split("=", 2)

		if ($Parts.Length -eq 2) {
			$Metadata[$Parts[0].Trim()] = $Parts[1].Trim()
		}
	}

	return $Metadata
}

#endregion

#region [主流程]

# 步驟一：驗證 SemVer、Tag、Maven 與 Setup 檔名完全一致。
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
	throw "Release 版本必須是三段 SemVer。"
}

if ($Tag -ne "" -and $Tag -ne "v$Version") {
	throw "Tag 與 Release 版本不一致：$Tag / $Version"
}

[xml]$Pom = Get-Content -LiteralPath (Join-Path $ProjectRoot "pom.xml") -Raw -Encoding utf8

if ([string]$Pom.project.version -ne $Version) {
	throw "Maven 與 Release 版本不一致。"
}

if ([System.IO.Path]::GetFileName($ResolvedInstaller) -ne "LinebotCommercial-Setup-$Version.exe") {
	throw "Setup 檔名與 Release 版本不一致。"
}

if (-not (Test-Path -LiteralPath $ResolvedInstaller -PathType Leaf)) {
	throw "找不到 Release Setup：$ResolvedInstaller"
}

# 步驟二：確認 SBOM、第三方聲明與商用欄位均可追溯。
foreach ($RequiredPath in @($SbomPath, $NoticesPath, $ReleaseMetadataPath, $LicensePath)) {
	if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
		throw "缺少 Release 必要檔案：$RequiredPath"
	}
}

$Sbom = Get-Content -LiteralPath $SbomPath -Raw -Encoding utf8 | ConvertFrom-Json

if ($Sbom.bomFormat -ne "CycloneDX" -or $null -eq $Sbom.components) {
	throw "CycloneDX SBOM 內容無效。"
}

if ($RequireCommercialMetadata) {
	$Metadata = Read-ReleaseMetadata
	$LicenseText = Get-Content -LiteralPath $LicensePath -Raw -Encoding utf8

	if ($Metadata.Values -contains "REPLACE_BEFORE_RELEASE" -or $Metadata.licenseStatus -ne "APPROVED") {
		throw "正式品牌或法律欄位尚未核准。"
	}

	if ([string]::IsNullOrWhiteSpace($Metadata.productName) -or [string]::IsNullOrWhiteSpace($Metadata.publisher)) {
		throw "正式產品名稱與 Publisher 不可為空白。"
	}

	$SupportUri = $null

	if (-not [Uri]::TryCreate($Metadata.supportUrl, [UriKind]::Absolute, [ref]$SupportUri) -or $SupportUri.Scheme -ne "https") {
		throw "正式 supportUrl 必須是絕對 HTTPS URL。"
	}

	if ($LicenseText -match 'Pre-release|internal verification only') {
		throw "安裝器仍使用預發佈 License。"
	}
}

# 步驟三：正式發佈要求有效 Authenticode，並輸出可寫入 Release Notes 的 SHA-256。
$Signature = Get-AuthenticodeSignature -LiteralPath $ResolvedInstaller

if ($RequireSignature -and $Signature.Status -ne "Valid") {
	throw "正式 Release 要求有效 Authenticode，目前狀態：$($Signature.Status)"
}

$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResolvedInstaller).Hash
Write-Output "SHA256=$Hash"
Write-Output "SIGNATURE_STATUS=$($Signature.Status)"

#endregion



