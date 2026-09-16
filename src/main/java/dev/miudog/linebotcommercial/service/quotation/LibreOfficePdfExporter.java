package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用隔離 LibreOffice profile 將 XLSX 轉為 PDF，適用 Linux 容器與本機 Docker。
 */
@Component
public class LibreOfficePdfExporter implements ExcelPdfExporter {

	private static final int MAXIMUM_OUTPUT_BYTES = 64 * 1024;

	private final String executable;

	// 方法：執行此方法定義的受控處理流程。
	public LibreOfficePdfExporter(
		@Value("${app.quotation.libreoffice-command:libreoffice}") String executable
	) {
		if (executable == null || executable.isBlank()) {
			throw new IllegalArgumentException("LibreOffice 執行檔不可留空");
		}
		this.executable = executable;
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	public void export(Path workbook, Path pdf, Duration timeout) throws IOException {
		validateArguments(workbook, pdf, timeout);
		Path input = workbook.toAbsolutePath().normalize();
		Path output = pdf.toAbsolutePath().normalize();
		Path profile = Files.createTempDirectory("linebot-libreoffice-profile-");
		Files.deleteIfExists(output);

		Process process = null;
		try {
			URI profileUri = profile.toUri();
			List<String> command = List.of(
				executable,
				"--headless",
				"--nologo",
				"--nodefault",
				"--nolockcheck",
				"--nofirststartwizard",
				"-env:UserInstallation=" + profileUri,
				"--convert-to",
				"pdf",
				"--outdir",
				input.getParent().toString(),
				input.toString()
			);
			process = new ProcessBuilder(command)
				.directory(input.getParent().toFile())
				.redirectErrorStream(true)
				.start();
			Process running = process;
			CompletableFuture<String> details = CompletableFuture.supplyAsync(
				() -> readOutput(running.getInputStream())
			);

			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				terminate(process);
				throw new ExcelPdfExportException("LibreOffice 匯出 PDF 逾時");
			}
			String outputDetails = completedOutput(details);
			if (process.exitValue() != 0) {
				throw new ExcelPdfExportException(safeMessage(outputDetails, input, output));
			}
			verifyPdf(output);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			if (process != null) terminate(process);
			throw new ExcelPdfExportException("LibreOffice 匯出 PDF 被中斷", exception);
		}
		catch (IOException exception) {
			if (exception instanceof ExcelPdfExportException typed) throw typed;

			throw new ExcelPdfExportException("無法啟動 LibreOffice", exception);
		}
		finally {
			if (process != null && process.isAlive()) terminate(process);
			deleteTemporaryTree(profile);
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static void validateArguments(
		Path workbook,
		Path pdf,
		Duration timeout
	) throws ExcelPdfExportException {
		if (workbook == null || pdf == null) throw new ExcelPdfExportException("XLSX 與 PDF 路徑不可為 null");

		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new ExcelPdfExportException("PDF 匯出逾時必須大於 0");
		}

		Path input = workbook.toAbsolutePath().normalize();
		Path output = pdf.toAbsolutePath().normalize();
		if (!Files.isRegularFile(input)) throw new ExcelPdfExportException("找不到要匯出的 XLSX");

		if (!input.getParent().equals(output.getParent())) {
			throw new ExcelPdfExportException("XLSX 與 PDF 必須位於同一隔離暫存目錄");
		}
		String inputName = input.getFileName().toString();
		String outputName = output.getFileName().toString();
		if (!inputName.toLowerCase().endsWith(".xlsx") || !outputName.toLowerCase().endsWith(".pdf")) {
			throw new ExcelPdfExportException("XLSX 或 PDF 副檔名不正確");
		}
		if (!inputName.substring(0, inputName.length() - 5)
			.equals(outputName.substring(0, outputName.length() - 4))) {
			throw new ExcelPdfExportException("XLSX 與 PDF 必須使用相同基本檔名");
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static void verifyPdf(Path pdf) throws IOException {
		if (!Files.isRegularFile(pdf) || Files.size(pdf) < 5) {
			throw new ExcelPdfExportException("LibreOffice 未產生有效 PDF");
		}
		byte[] signature = new byte[5];
		try (InputStream input = Files.newInputStream(pdf)) {
			if (input.read(signature) != signature.length
				|| !"%PDF-".equals(new String(signature, StandardCharsets.US_ASCII))) {
				throw new ExcelPdfExportException("LibreOffice 產物不是 PDF");
			}
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String readOutput(InputStream input) {
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

	// 方法：執行此方法定義的受控處理流程。
	private static String completedOutput(CompletableFuture<String> output) {
		try {
			return output.get(200, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			return "";
		}
	}

	// 方法：執行此方法定義的受控處理流程。
	private static String safeMessage(String details, Path workbook, Path pdf) {
		if (details == null || details.isBlank()) return "LibreOffice 匯出 PDF 失敗";

		String firstLine = details.lines()
			.map(String::strip)
			.filter(line -> !line.isBlank())
			.findFirst()
			.orElse("LibreOffice 匯出 PDF 失敗")
			.replace(workbook.toString(), "[WORKBOOK]")
			.replace(pdf.toString(), "[PDF]");
		return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
	}

	// 方法：執行此方法定義的受控處理流程。
	private static void terminate(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	// 方法：執行此方法定義的受控處理流程。
	private static void deleteTemporaryTree(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// 暫存 profile 清理失敗不掩蓋原始轉檔結果。
				}
			});
		}
		catch (IOException ignored) {
			// 暫存 profile 清理失敗不掩蓋原始轉檔結果。
		}
	}
}
