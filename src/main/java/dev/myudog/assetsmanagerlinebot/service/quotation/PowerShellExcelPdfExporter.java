package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 以固定 PowerShell 腳本在隱藏的本機 Microsoft Excel COM 程序中匯出 PDF。
 */
@Component
public class PowerShellExcelPdfExporter implements ExcelPdfExporter {

	private static final int MAXIMUM_OUTPUT_BYTES = 64 * 1024;
	private static final Path PROJECT_SCRIPT_PATH = Paths.get(
		System.getProperty("user.dir"),
		"scripts",
		"export-quotation-pdf.ps1"
	).toAbsolutePath().normalize();

	private final Path scriptPath;

	// 方法：正式環境固定使用專案 scripts 內的 Excel PDF 匯出腳本。
	public PowerShellExcelPdfExporter() {
		this(PROJECT_SCRIPT_PATH);
	}

	// 方法：供同套件測試注入可控腳本，不暴露為 Spring 設定入口。
	PowerShellExcelPdfExporter(Path scriptPath) {
		if (scriptPath == null) throw new IllegalArgumentException("Excel PDF 匯出腳本不可為 null");

		this.scriptPath = scriptPath.toAbsolutePath().normalize();
	}

	// 方法：以參數陣列啟動固定腳本，逾時時終止程序並回傳受控錯誤。
	@Override
	public void export(Path workbook, Path pdf, Duration timeout) throws IOException {
		validateArguments(workbook, pdf, timeout);
		List<String> command = List.of(
			"powershell.exe",
			"-NoLogo",
			"-NoProfile",
			"-NonInteractive",
			"-ExecutionPolicy",
			"Bypass",
			"-File",
			scriptPath.toString(),
			"-WorkbookPath",
			workbook.toString(),
			"-PdfPath",
			pdf.toString()
		);

		Process process;
		try {
			// 外部程序：只執行專案內固定腳本，路徑以獨立參數傳入而不拼接 Shell 指令。
			process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();
		}
		catch (IOException exception) {
			throw new ExcelPdfExportException("無法啟動 PowerShell 或 Microsoft Excel", exception);
		}

		CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				terminate(process);
				throw new ExcelPdfExportException("Microsoft Excel 匯出 PDF 逾時");
			}

			String details = completedOutput(output);
			if (process.exitValue() != 0) {
				throw new ExcelPdfExportException(
					safeProcessMessage(details, workbook, pdf)
				);
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			terminate(process);
			throw new ExcelPdfExportException("Microsoft Excel 匯出 PDF 被中斷", exception);
		}
		finally {
			if (process.isAlive()) terminate(process);
		}
	}

	// 方法：驗證腳本、輸入輸出與逾時均來自可信且明確的本機路徑。
	private void validateArguments(Path workbook, Path pdf, Duration timeout) throws ExcelPdfExportException {
		if (!Files.isRegularFile(scriptPath)) throw new ExcelPdfExportException("找不到固定 Excel PDF 匯出腳本");

		if (workbook == null || pdf == null) throw new ExcelPdfExportException("Excel 與 PDF 路徑不可為 null");

		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new ExcelPdfExportException("Excel PDF 匯出逾時必須大於 0");
		}

		Path input = workbook.toAbsolutePath().normalize();
		Path output = pdf.toAbsolutePath().normalize();
		if (!Files.isRegularFile(input)) throw new ExcelPdfExportException("找不到要匯出的 Excel 檔案");

		if (!input.getParent().equals(output.getParent())) {
			throw new ExcelPdfExportException("Excel 與 PDF 必須位於同一報價資料夾");
		}

		String inputName = input.getFileName().toString();
		String outputName = output.getFileName().toString();
		if (!inputName.toLowerCase().endsWith(".xlsx") || !outputName.toLowerCase().endsWith(".pdf")) {
			throw new ExcelPdfExportException("Excel 或 PDF 副檔名不正確");
		}

		String inputBaseName = inputName.substring(0, inputName.length() - 5);
		String outputBaseName = outputName.substring(0, outputName.length() - 4);
		if (!inputBaseName.equals(outputBaseName)) {
			throw new ExcelPdfExportException("Excel 與 PDF 必須使用相同基本檔名");
		}
	}

	// 方法：限制子程序輸出大小，避免錯誤程序耗盡記憶體或把敏感內容寫入例外。
	private String readOutput(InputStream input) {
		try (input) {
			ByteArrayOutputStream saved = new ByteArrayOutputStream(MAXIMUM_OUTPUT_BYTES);
			byte[] buffer = new byte[4096];
			int count;
			while ((count = input.read(buffer)) != -1) {
				int remaining = MAXIMUM_OUTPUT_BYTES - saved.size();
				if (remaining > 0) saved.write(buffer, 0, Math.min(count, remaining));
			}
			return saved.toString(StandardCharsets.UTF_8).trim();
		}
		catch (IOException exception) {
			return "無法讀取 Excel 匯出程序結果";
		}
	}

	// 方法：等待已結束程序的有限輸出讀取，避免讀取執行緒無限懸掛。
	private String completedOutput(CompletableFuture<String> output) throws ExcelPdfExportException {
		try {
			return output.get(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ExcelPdfExportException("讀取 Excel 匯出結果被中斷", exception);
		}
		catch (ExecutionException | TimeoutException exception) {
			throw new ExcelPdfExportException("無法讀取 Excel 匯出程序結果", exception);
		}
	}

	// 方法：移除程序輸出中的本機絕對路徑並限制長度，只保留可供管理重試判斷的摘要。
	private String safeProcessMessage(String details, Path workbook, Path pdf) {
		if (details == null || details.isBlank()) return "Microsoft Excel 匯出 PDF 失敗";

		String safe = details
			.replace(scriptPath.toString(), "[固定腳本]")
			.replace(workbook.toAbsolutePath().normalize().toString(), "[Excel 檔案]")
			.replace(pdf.toAbsolutePath().normalize().toString(), "[PDF 檔案]")
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim();
		return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
	}

	// 方法：先正常終止，短暫等待後再強制停止受控 Excel 匯出程序。
	private void terminate(Process process) {
		List<ProcessHandle> descendants = process.descendants().toList();
		process.destroy();
		descendants.forEach(ProcessHandle::destroy);
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		}
	}
}
