param(
	[Parameter(Mandatory = $true)]
	[string]$ArtifactPath,
	[string]$TimestampUrl = "http://timestamp.digicert.com"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

#region [欄位]

$ResolvedArtifact = [System.IO.Path]::GetFullPath($ArtifactPath)
$TemporaryCertificate = Join-Path ([System.IO.Path]::GetTempPath()) ("assets-manager-signing-" + [Guid]::NewGuid() + ".pfx")
$CertificateBase64 = [Environment]::GetEnvironmentVariable("SIGNING_CERTIFICATE_BASE64")
$CertificatePassword = [Environment]::GetEnvironmentVariable("SIGNING_CERTIFICATE_PASSWORD")
$ExistingCertificateThumbprints = @(
	Get-ChildItem -LiteralPath "Cert:\CurrentUser\My" |
		Select-Object -ExpandProperty Thumbprint
)
$ImportedCertificates = @()
$SigningCertificate = $null

#endregion

#region [方法]

# 方法：尋找 Windows SDK 內最新的 x64 SignTool。
function Find-SignTool {
	$Command = Get-Command "signtool.exe" -ErrorAction SilentlyContinue

	if ($null -ne $Command) {
		return $Command.Source

	}

	$WindowsKitsRoot = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
	$Candidate = Get-ChildItem -LiteralPath $WindowsKitsRoot -Filter "signtool.exe" -Recurse -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
		Sort-Object FullName -Descending |
		Select-Object -First 1

	if ($null -eq $Candidate) {
		throw "找不到 Windows SDK SignTool。"
	}

	return $Candidate.FullName
}

#endregion

#region [主流程]

if (-not (Test-Path -LiteralPath $ResolvedArtifact -PathType Leaf)) {
	throw "找不到待簽章檔案：$ResolvedArtifact"
}

if ([string]::IsNullOrWhiteSpace($CertificateBase64) -or [string]::IsNullOrWhiteSpace($CertificatePassword)) {
	throw "缺少受保護的 Windows 簽章憑證或密碼環境變數。"
}

try {
	# 外部函式：只在 runner 暫存目錄還原受 GitHub Environment 保護的 PFX。
	[System.IO.File]::WriteAllBytes(
		$TemporaryCertificate,
		[Convert]::FromBase64String($CertificateBase64)
	)
	$SecurePassword = ConvertTo-SecureString $CertificatePassword -AsPlainText -Force

	# 外部函式：匯入目前使用者憑證庫，避免把 PFX 密碼放入 SignTool 命令列。
	$ImportedCertificates = @(
		Import-PfxCertificate -FilePath $TemporaryCertificate -CertStoreLocation "Cert:\CurrentUser\My" -Password $SecurePassword
	)
	$SigningCertificate = $ImportedCertificates |
		Where-Object { $_.HasPrivateKey } |
		Select-Object -First 1

	if ($null -eq $SigningCertificate) {
		throw "PFX 不含可用的私密金鑰憑證。"
	}

	$SignTool = Find-SignTool

	# 外部函式：以 SHA-256 與可信任時間戳簽署唯一 Release Setup。
	& $SignTool sign /sha1 $SigningCertificate.Thumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $ResolvedArtifact

	if ($LASTEXITCODE -ne 0) {
		throw "SignTool 簽章失敗，exit code：$LASTEXITCODE"
	}

	$Signature = Get-AuthenticodeSignature -LiteralPath $ResolvedArtifact

	if ($Signature.Status -ne "Valid") {
		throw "簽章後 Authenticode 驗證失敗：$($Signature.Status)"
	}
}
finally {
	foreach ($ImportedCertificate in $ImportedCertificates) {
		if ($ExistingCertificateThumbprints -notcontains $ImportedCertificate.Thumbprint) {
			Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($ImportedCertificate.Thumbprint)" -Force -ErrorAction SilentlyContinue
		}
	}

	Remove-Item -LiteralPath $TemporaryCertificate -Force -ErrorAction SilentlyContinue
}

#endregion


