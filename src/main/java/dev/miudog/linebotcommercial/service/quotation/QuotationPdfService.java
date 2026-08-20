package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.ai.ExtractedSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

/**
 * 將既有正式 XLSX 交給本機 Microsoft Excel 匯出 PDF，並保存可重試狀態。
 */
@Service
public class QuotationPdfService {

	private static final String QUOTATION_DIRECTORY = "報價單";
	private static final int MAXIMUM_ERROR_LENGTH = 1000;
	private static final Set<String> EXPORTABLE_STATUSES = Set.of("GENERATING_PDF", "PDF_FAILED");

	private final QuotationPdfStore store;
	private final ExcelPdfExporter exporter;
	private final Path outputRoot;
	private final Duration timeout;

	// 方法：由應用程式設定建立 Excel PDF 匯出服務。
	@Autowired
	public QuotationPdfService(
		QuotationPdfStore store,
		ExcelPdfExporter exporter,
		@Value("${app.quotation.root-path:}") String outputRoot,
		@Value("${app.quotation.pdf-timeout-seconds:90}") long timeoutSeconds
	) {
		this(
			store,
			exporter,
			outputRoot == null || outputRoot.isBlank() ? null : Paths.get(outputRoot),
			validTimeout(timeoutSeconds)
		);
	}

	// 方法：建立可由單元測試注入假匯出器與暫存路徑的服務。
	QuotationPdfService(
		QuotationPdfStore store,
		ExcelPdfExporter exporter,
		Path outputRoot,
		Duration timeout
	) {
		this.store = store;
		this.exporter = exporter;
		this.outputRoot = outputRoot == null ? null : outputRoot.toAbsolutePath().normalize();
		this.timeout = timeout;
	}

	// 方法：回報是否已設定可限制輸出的報價根目錄。
	public boolean isConfigured() {
		return outputRoot != null;
	}

	// 方法：以既有報價、XLSX 路徑及流水號匯出 PDF。
	public PdfExportResult export(long quotationId) {
		return exportExisting(quotationId, false);
	}

	// 方法：只重試 PDF_FAILED 報價，不重新配置流水號或產生新資料夾。
	public PdfExportResult retry(long quotationId) {
		return exportExisting(quotationId, true);
	}

	// 方法：執行初次或重試匯出，共用同一份既有報價工作資料。
	private PdfExportResult exportExisting(long quotationId, boolean retry) {
		requireConfigured();
		QuotationPdfStore.PdfJob job = store.find(quotationId)
			.orElseThrow(() -> failure("PDF_JOB_NOT_FOUND", "找不到已完成 Excel 的報價"));
		validateStatus(job, retry);

		String relativePdfPath = null;
		Path pdfPath = null;
		try {
			Path workbookPath = safeQuotationPath(job.xlsxRelativePath(), ".xlsx");
			relativePdfPath = pdfRelativePath(job.xlsxRelativePath());
			pdfPath = safeQuotationPath(relativePdfPath, ".pdf");
			if (!Files.isRegularFile(workbookPath)) throw failure("XLSX_NOT_FOUND", "報價 Excel 檔案不存在");

			ensureNoSymlinkEscape(workbookPath, pdfPath);

			store.markGenerating(quotationId, relativePdfPath);
			// 檔案系統：只移除同一報價、同基本檔名的舊失敗 PDF，絕不修改 XLSX。
			Files.deleteIfExists(pdfPath);
			// 外部 API：交由固定 Microsoft Excel COM 腳本沿用活頁簿原列印設定匯出。
			exporter.export(workbookPath, pdfPath, timeout);
			validatePdf(pdfPath);

			long fileSize = Files.size(pdfPath);
			String contentHash = sha256(pdfPath);
			store.markReady(quotationId, relativePdfPath, contentHash, fileSize);
			return new PdfExportResult(quotationId, job.quotationNumber(), workbookPath, pdfPath, contentHash, fileSize);
		}
		catch (Exception exception) {
			deleteIncompletePdf(pdfPath);
			String message = safeErrorMessage(exception);
			store.markFailed(quotationId, relativePdfPath, message);
			if (exception instanceof QuotationAdminException adminException) throw adminException;

			throw failure("PDF_EXPORT_FAILED", "Excel 匯出 PDF 失敗：" + message, exception);
		}
	}

	// 方法：限制初次匯出與重試可接受的既有狀態，避免覆寫 READY 報價。
	private void validateStatus(QuotationPdfStore.PdfJob job, boolean retry) {
		if (retry && !"PDF_FAILED".equals(job.status())) {
			throw failure("PDF_RETRY_NOT_ALLOWED", "只有 PDF_FAILED 報價可以重試 PDF");
		}

		if (!EXPORTABLE_STATUSES.contains(job.status())) {
			throw failure("PDF_EXPORT_NOT_ALLOWED", "目前報價狀態不可匯出 PDF：" + job.status());
		}
	}

	// 方法：將既有 XLSX 相對路徑改為同資料夾、同基本檔名的 PDF 相對路徑。
	private String pdfRelativePath(String xlsxRelativePath) {
		if (xlsxRelativePath == null || !xlsxRelativePath.toLowerCase().endsWith(".xlsx")) {
			throw failure("INVALID_XLSX_PATH", "報價 Excel 路徑或副檔名不正確");
		}

		return xlsxRelativePath.substring(0, xlsxRelativePath.length() - 5) + ".pdf";
	}

	// 方法：將資料庫相對路徑限制在設定根目錄的報價單子目錄與指定副檔名內。
	private Path safeQuotationPath(String relativePath, String extension) {
		if (relativePath == null || relativePath.isBlank()) {
			throw failure("INVALID_OUTPUT_PATH", "報價檔案路徑不可留空");
		}

		Path relative = Paths.get(relativePath);
		if (relative.isAbsolute()) throw failure("INVALID_OUTPUT_PATH", "報價檔案路徑必須是相對路徑");

		Path quotationRoot = outputRoot.resolve(QUOTATION_DIRECTORY).normalize();
		Path resolved = outputRoot.resolve(relative).normalize();
		if (!resolved.startsWith(quotationRoot)) throw failure("INVALID_OUTPUT_PATH", "報價檔案路徑超出報價單目錄");

		if (!resolved.getFileName().toString().toLowerCase().endsWith(extension)) {
			throw failure("INVALID_OUTPUT_PATH", "報價檔案副檔名不正確");
		}

		return resolved;
	}

	// 方法：以實體父目錄再次確認符號連結沒有把 Excel 或 PDF 導向報價單目錄外。
	private void ensureNoSymlinkEscape(Path workbookPath, Path pdfPath) throws IOException {
		Path realQuotationRoot = outputRoot.resolve(QUOTATION_DIRECTORY).toRealPath();
		Path realWorkbook = workbookPath.toRealPath();
		Path realPdfParent = pdfPath.getParent().toRealPath();
		if (!realWorkbook.startsWith(realQuotationRoot) || !realPdfParent.startsWith(realQuotationRoot)) {
			throw failure("INVALID_OUTPUT_PATH", "報價檔案路徑透過符號連結超出報價單目錄");
		}
	}

	// 方法：確認 Excel 確實產生一般檔案且具有 PDF 魔術標頭。
	private void validatePdf(Path pdfPath) throws IOException {
		if (!Files.isRegularFile(pdfPath) || Files.size(pdfPath) < 5) {
			throw new ExcelPdfExportException("Microsoft Excel 未建立有效 PDF");
		}

		byte[] header = new byte[5];
		try (InputStream input = Files.newInputStream(pdfPath)) {
			if (input.read(header) != header.length) throw new ExcelPdfExportException("PDF 檔案標頭不完整");
		}
		if (!"%PDF-".equals(new String(header, java.nio.charset.StandardCharsets.US_ASCII))) {
			throw new ExcelPdfExportException("Microsoft Excel 產出內容不是 PDF");
		}
	}

	// 方法：計算正式 PDF 的 SHA-256，供資料庫與下載流程核對檔案身分。
	private String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) >= 0) {
					if (count > 0) digest.update(buffer, 0, count);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境缺少 SHA-256", exception);
		}
	}

	// 方法：清理由本次匯出留下的同名不完整 PDF，保留原 XLSX 供同序號重試。
	private void deleteIncompletePdf(Path pdfPath) {
		if (pdfPath == null) return;

		try {
			Files.deleteIfExists(pdfPath);
		}
		catch (IOException ignored) {
			// 清理失敗不得覆蓋原始 Excel 匯出錯誤與資料庫 PDF_FAILED 狀態。
		}
	}

	// 方法：移除換行並限制資料庫錯誤摘要長度，避免記錄程序輸出中的大量內容。
	private String safeErrorMessage(Exception exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();

		String root = outputRoot == null ? "" : outputRoot.toString();
		String safe = message.replace('\r', ' ').replace('\n', ' ').trim();
		if (!root.isBlank()) {
			safe = safe
				.replace(root, "<QUOTATION_OUTPUT_PATH>")
				.replace(root.replace('\\', '/'), "<QUOTATION_OUTPUT_PATH>");
		}

		return safe.length() <= MAXIMUM_ERROR_LENGTH ? safe : safe.substring(0, MAXIMUM_ERROR_LENGTH);
	}

	// 方法：要求管理員先設定報價輸出根目錄。
	private void requireConfigured() {
		if (outputRoot == null) throw failure("PDF_NOT_CONFIGURED", "尚未設定報價輸出根目錄");
	}

	// 方法：將正整數秒數轉成受控 Excel 程序逾時。
	private static Duration validTimeout(long timeoutSeconds) {
		if (timeoutSeconds <= 0 || timeoutSeconds > 3600) {
			throw new IllegalArgumentException("PDF 匯出逾時必須介於 1 至 3600 秒");
		}

		return Duration.ofSeconds(timeoutSeconds);
	}

	// 方法：建立穩定錯誤代碼且不揭露本機絕對路徑的 PDF 失敗。
	private QuotationAdminException failure(String code, String message) {
		return new QuotationAdminException(code, message);
	}

	// 方法：建立保留根因供內部診斷的 PDF 失敗。
	private QuotationAdminException failure(String code, String message, Throwable cause) {
		return new QuotationAdminException(code, message, cause);
	}

	/**
	 * 舊文字報價流程尚未遷移至正式 XLSX 記錄；保留方法簽章避免破壞既有呼叫端。
	 */
	// 方法：拒絕未綁定正式報價與 XLSX 的舊 PDF 直接產生流程。
	@Deprecated
	public Path generate(ExtractedSpec spec, QuotationAmounts amounts, byte[] infoImage) {
		throw new UnsupportedOperationException("請先建立正式 XLSX，再以報價識別碼匯出 PDF");
	}

	public record PdfExportResult(
		long quotationId,
		String quotationNumber,
		Path workbookPath,
		Path pdfPath,
		String contentHash,
		long fileSize
	) {}
}
