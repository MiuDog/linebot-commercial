param(
	[Parameter(Mandatory = $true)]
	[string]$WorkbookPath,

	[Parameter(Mandatory = $true)]
	[string]$PdfPath
)

$ErrorActionPreference = "Stop"
$excel = $null
$workbook = $null
$failureMessage = $null

try {
	# 步驟 1：只接受既有 XLSX 與同目錄、同基本檔名的 PDF 目標。.
	$inputFile = [System.IO.Path]::GetFullPath($WorkbookPath)
	$outputFile = [System.IO.Path]::GetFullPath($PdfPath)
	if (-not [System.IO.File]::Exists($inputFile)) {
		throw "找不到要匯出的 Excel 檔案"
	}

	if ([System.IO.Path]::GetExtension($inputFile) -ine ".xlsx") {
		throw "輸入檔必須是 XLSX"
	}

	if ([System.IO.Path]::GetExtension($outputFile) -ine ".pdf") {
		throw "輸出檔必須是 PDF"
	}

	$inputDirectory = [System.IO.Path]::GetDirectoryName($inputFile)
	$outputDirectory = [System.IO.Path]::GetDirectoryName($outputFile)
	$inputBaseName = [System.IO.Path]::GetFileNameWithoutExtension($inputFile)
	$outputBaseName = [System.IO.Path]::GetFileNameWithoutExtension($outputFile)
	if ($inputDirectory -ine $outputDirectory -or $inputBaseName -cne $outputBaseName) {
		throw "Excel 與 PDF 必須位於同一資料夾且使用相同基本檔名"
	}

	# 外部 API：啟動隱藏 Microsoft Excel COM，唯讀開啟活頁簿並沿用原列印設定匯出 PDF。.
	$excel = New-Object -ComObject Excel.Application
	$excel.Visible = $false
	$excel.DisplayAlerts = $false
	$excel.ScreenUpdating = $false
	$workbook = $excel.Workbooks.Open($inputFile, 0, $true)
	$workbook.ExportAsFixedFormat(0, $outputFile)

	if (-not [System.IO.File]::Exists($outputFile)) {
		throw "Microsoft Excel 未建立 PDF"
	}
}
catch {
	# 錯誤邊界：只保存 COM 錯誤摘要，不輸出腳本堆疊或本機檔案路徑。.
	$failureMessage = $_.Exception.Message
}
finally {
	# 外部 API：不論成功或失敗都關閉活頁簿與 Excel，避免背景程序鎖住報價檔案。.
	if ($null -ne $workbook) {
		try {
			$workbook.Close($false)
		}
		catch {
			# 清理錯誤不得阻止 Excel.Quit，原始匯出錯誤由呼叫端保存。.
		}
		finally {
			try {
				[void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($workbook)
			}
			catch {
				# COM 參照釋放失敗不得阻止後續 Excel.Quit。.
			}
		}
	}

	if ($null -ne $excel) {
		try {
			$excel.Quit()
		}
		catch {
			# Java 逾時保護仍會終止本次受控程序樹。.
		}
		finally {
			try {
				[void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($excel)
			}
			catch {
				# 程序結束時仍會釋放本次獨立 Excel COM 伺服器。.
			}
		}
	}

	[GC]::Collect()
	[GC]::WaitForPendingFinalizers()
}

if ($null -ne $failureMessage) {
	[Console]::Error.WriteLine($failureMessage)
	exit 1
}
