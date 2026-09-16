#requires -Version 7.0
$ErrorActionPreference = 'Stop'

# 僅保全明確列出的公司資產；雜湊一致後才允許後續移除原檔。
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$packageRoot = Join-Path $projectRoot 'company-assets-local/initial'
$assetPaths = @(
	'outputs/excel-templates',
	'src/main/resources/quotation/template-definitions.json',
	'src/main/resources/db/migration/V1__baseline.sql',
	'src/test/resources/schema.sql'
)
$manifest = @()
foreach ($assetPath in $assetPaths) {
	$source = [IO.Path]::GetFullPath((Join-Path $projectRoot $assetPath))
	if (-not $source.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar)) {
		throw '資產路徑超出專案範圍。'
	}
	$files = if (Test-Path -LiteralPath $source -PathType Container) {
		Get-ChildItem -LiteralPath $source -File -Recurse
	} else { Get-Item -LiteralPath $source }
	foreach ($file in $files) {
		$relative = $file.FullName.Substring($projectRoot.Length + 1)
		$destination = Join-Path $packageRoot $relative
		$sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
		if (Test-Path -LiteralPath $destination) {
			if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
				throw "既有保全檔不同，停止覆寫：$relative"
			}
		} else {
			New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
			Copy-Item -LiteralPath $file.FullName -Destination $destination
		}
		if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
			throw "保全驗證失敗：$relative"
		}
		$manifest += [ordered]@{ path = $relative.Replace('\', '/'); sha256 = $sourceHash; bytes = $file.Length }
	}
}
# 清單由執行結果產生，不含任何憑證。
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $packageRoot 'preservation-manifest.json') -Encoding utf8
Write-Output "Verified $($manifest.Count) preserved files. Originals have not been removed."
