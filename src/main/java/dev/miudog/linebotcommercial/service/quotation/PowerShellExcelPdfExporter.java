package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

	// 方法：解析實體腳本路徑；若專案路徑不存在則由 classpath 提取至暫存檔。
	private Path resolveScript() throws ExcelPdfExportException {
		if (Files.isRegularFile(scriptPath)) return scriptPath;

		try (InputStream stream = getClass().getResourceAsStream("/scripts/export-quotation-pdf.ps1")) {
			if (stream != null) {
				Path tempScript = Files.createTempFile("export-quotation-pdf-", ".ps1");
				tempScript.toFile().deleteOnExit();
				Files.copy(stream, tempScript, StandardCopyOption.REPLACE_EXISTING);
				return tempScript;
			}
		}
		catch (IOException exception) {
			throw new ExcelPdfExportException("無法提取內嵌 Excel PDF 匯出腳本", exception);
		}

		throw new ExcelPdfExportException("找不到固定 Excel PDF 匯出腳本");
	}

	// 方法：以參數陣列啟動固定腳本，逾時時終止程序並回傳受控錯誤。
	@Override
	public void export(Path workbook, Path pdf, Duration timeout) throws IOException {
		Path targetScript = resolveScript();
		validateArguments(targetScript, workbook, pdf, timeout);
		List<String> command = List.of(
			"powershell.exe",
			"-NoLogo",
			"-NoProfile",
			"-NonInteractive",
			"-ExecutionPolicy",
			"Bypass",
			"-File",
			targetScript.toString(),
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
	private void validateArguments(Path targetScript, Path workbook, Path pdf, Duration timeout) throws ExcelPdfExportException {
		if (!Files.isRegularFile(targetScript)) throw new ExcelPdfExportException("找不到固定 Excel PDF 匯出腳本");

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

			return saved.toString(StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			return "";
		}
	}

	// 方法：安全取出已完成的輸出字串，發生非預期例外時回傳空字串。
	private String completedOutput(CompletableFuture<String> output) {
		try {
			return output.get(200, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			return "";
		}
	}

	// 方法：安全遮蔽子程序訊息中非結構化路徑，只保留簡潔錯誤原因。
	private String safeProcessMessage(String details, Path workbook, Path pdf) {
		if (details == null || details.isBlank()) return "Microsoft Excel 匯出 PDF 失敗";

		String line = details.lines()
			.map(String::trim)
			.filter(text -> !text.isBlank())
			.findFirst()
			.orElse("Microsoft Excel 匯出 PDF 失敗");
		String masked = line.replace(workbook.toString(), "[WORKBOOK]")
			.replace(pdf.toString(), "[PDF]");

		if (masked.length() > 200) return masked.substring(0, 200);

		return masked;
	}

	// 方法：以受控方式強制終止 Excel COM 程序樹。
	private void terminate(Process process) {
		try {
			process.destroyForcibly();
		}
		catch (Exception exception) {
			// 例外邊界：終止失敗不掩蓋呼叫端的原例外。
		}
	}
}
