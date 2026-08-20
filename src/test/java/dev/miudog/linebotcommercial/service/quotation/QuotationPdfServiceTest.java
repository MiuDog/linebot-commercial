package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationPdfServiceTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void exportsBesideTheExistingWorkbookAndMarksTheSameQuotationReady() throws IOException {
		FakeQuotationPdfStore store = preparedStore("GENERATING_PDF");
		RecordingExporter exporter = new RecordingExporter(false);
		QuotationPdfService service = service(store, exporter);

		QuotationPdfService.PdfExportResult result = service.export(41);

		assertThat(result.quotationId()).isEqualTo(41);
		assertThat(result.quotationNumber()).isEqualTo("20260811-01");
		assertThat(result.pdfPath().getFileName().toString()).isEqualTo("正定公司-中壢案 20260811-01.pdf");
		assertThat(exporter.input).isEqualTo(workbookPath());
		assertThat(exporter.output).isEqualTo(result.pdfPath());
		assertThat(store.status).isEqualTo("READY");
		assertThat(store.relativePdfPath).isEqualTo(
			Path.of("報價單", "20260811-01", "正定公司-中壢案 20260811-01.pdf").toString()
		);
		assertThat(store.contentHash).hasSize(64);
		assertThat(store.fileSize).isPositive();
	}

	@Test
	void preservesTheWorkbookAndMarksPdfFailedWhenExcelExportFails() throws IOException {
		FakeQuotationPdfStore store = preparedStore("GENERATING_PDF");
		RecordingExporter exporter = new RecordingExporter(true);
		QuotationPdfService service = service(store, exporter);

		assertThatThrownBy(() -> service.export(41))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("Excel 匯出 PDF 失敗");

		assertThat(workbookPath()).isRegularFile();
		assertThat(store.status).isEqualTo("PDF_FAILED");
		assertThat(store.errorMessage).contains("受控測試失敗");
		assertThat(store.relativePdfPath).isEqualTo(
			Path.of("報價單", "20260811-01", "正定公司-中壢案 20260811-01.pdf").toString()
		);
	}

	@Test
	void retriesTheFailedQuotationWithoutChangingItsNumberOrPaths() throws IOException {
		FakeQuotationPdfStore store = preparedStore("PDF_FAILED");
		RecordingExporter exporter = new RecordingExporter(false);
		QuotationPdfService service = service(store, exporter);

		QuotationPdfService.PdfExportResult result = service.retry(41);

		assertThat(result.quotationNumber()).isEqualTo("20260811-01");
		assertThat(exporter.input).isEqualTo(workbookPath());
		assertThat(exporter.output).isEqualTo(
			workbookPath().resolveSibling("正定公司-中壢案 20260811-01.pdf")
		);
		assertThat(store.markGeneratingCount).isEqualTo(1);
		assertThat(store.status).isEqualTo("READY");
	}

	@Test
	void marksPdfFailedWhenTheControlledExcelProcessTimesOut() throws IOException {
		FakeQuotationPdfStore store = preparedStore("GENERATING_PDF");
		ExcelPdfExporter exporter = (workbook, pdf, timeout) -> {
			throw new ExcelPdfExportException("Microsoft Excel 匯出 PDF 逾時");

		};
		QuotationPdfService service = service(store, exporter);

		assertThatThrownBy(() -> service.export(41))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("逾時");

		assertThat(workbookPath()).isRegularFile();
		assertThat(store.status).isEqualTo("PDF_FAILED");
		assertThat(store.errorMessage).contains("逾時");
	}

	@Test
	void removesTheConfiguredRootFromPersistedFailureDetails() throws IOException {
		FakeQuotationPdfStore store = preparedStore("GENERATING_PDF");
		ExcelPdfExporter exporter = (workbook, pdf, timeout) -> {
			throw new ExcelPdfExportException("failed at " + workbook);

		};
		QuotationPdfService service = service(store, exporter);

		assertThatThrownBy(() -> service.export(41))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageNotContaining(temporaryDirectory.toString());

		assertThat(store.errorMessage)
			.doesNotContain(temporaryDirectory.toString())
			.contains("<QUOTATION_OUTPUT_PATH>");
	}

	@Test
	void rejectsDatabasePathsThatEscapeTheConfiguredQuotationDirectory() {
		FakeQuotationPdfStore store = new FakeQuotationPdfStore(
			new QuotationPdfStore.PdfJob(41, "20260811-01", "GENERATING_PDF", "../../outside.xlsx")
		);
		QuotationPdfService service = service(store, new RecordingExporter(false));

		assertThatThrownBy(() -> service.export(41))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("路徑");

		assertThat(store.status).isEqualTo("PDF_FAILED");
	}

	@Test
	void fixedPowerShellScriptClosesWorkbookAndExcelInFinally() throws IOException {
		String script = Files.readString(
			Path.of("scripts", "export-quotation-pdf.ps1"),
			StandardCharsets.UTF_8
		);

		assertThat(script)
			.contains("ExportAsFixedFormat", "finally", "$workbook.Close($false)", "$excel.Quit()")
			.doesNotContain("Invoke-Expression");
	}

	@Test
	void productionPdfExporterDoesNotAcceptAConfigurableScriptPath() {
		List<Constructor<?>> publicConstructors = List.of(PowerShellExcelPdfExporter.class.getConstructors());

		assertThat(publicConstructors).singleElement()
			.extracting(Constructor::getParameterCount)
			.isEqualTo(0);
	}

	private QuotationPdfService service(QuotationPdfStore store, ExcelPdfExporter exporter) {
		return new QuotationPdfService(store, exporter, temporaryDirectory, Duration.ofSeconds(2));
	}

	private FakeQuotationPdfStore preparedStore(String status) throws IOException {
		Files.createDirectories(workbookPath().getParent());
		Files.writeString(workbookPath(), "xlsx-test", StandardCharsets.UTF_8);
		return new FakeQuotationPdfStore(
			new QuotationPdfStore.PdfJob(
				41,
				"20260811-01",
				status,
				Path.of("報價單", "20260811-01", "正定公司-中壢案 20260811-01.xlsx").toString()
			)
		);
	}

	private Path workbookPath() {
		return temporaryDirectory
			.resolve("報價單")
			.resolve("20260811-01")
			.resolve("正定公司-中壢案 20260811-01.xlsx");
	}

	private static final class RecordingExporter implements ExcelPdfExporter {

		private final boolean shouldFail;
		private Path input;
		private Path output;

		private RecordingExporter(boolean shouldFail) {
			this.shouldFail = shouldFail;
		}

		// 方法：模擬 Excel 匯出，成功時建立最小 PDF 標頭，失敗時回傳受控例外。
		@Override
		public void export(Path workbook, Path pdf, Duration timeout) throws IOException {
			input = workbook;
			output = pdf;
			if (shouldFail) throw new ExcelPdfExportException("受控測試失敗");

			Files.writeString(pdf, "%PDF-1.4\n%%EOF", StandardCharsets.US_ASCII);
		}
	}

	private static final class FakeQuotationPdfStore implements QuotationPdfStore {

		private final PdfJob job;
		private String status;
		private String relativePdfPath;
		private String contentHash;
		private Long fileSize;
		private String errorMessage;
		private int markGeneratingCount;

		private FakeQuotationPdfStore(PdfJob job) {
			this.job = job;
			this.status = job.status();
		}

		// 方法：回傳固定報價工作供單元測試驗證。
		@Override
		public Optional<PdfJob> find(long quotationId) {
			return quotationId == job.quotationId() ? Optional.of(job) : Optional.empty();
		}

		// 方法：記錄進入 PDF 產生狀態且不配置新流水號。
		@Override
		public void markGenerating(long quotationId, String relativePdfPath) {
			status = "GENERATING_PDF";
			this.relativePdfPath = relativePdfPath;
			markGeneratingCount++;
		}

		// 方法：記錄 PDF 完成後的相對路徑與檔案指紋。
		@Override
		public void markReady(long quotationId, String relativePdfPath, String contentHash, long fileSize) {
			status = "READY";
			this.relativePdfPath = relativePdfPath;
			this.contentHash = contentHash;
			this.fileSize = fileSize;
		}

		// 方法：記錄 PDF 失敗，並保留原報價與原 XLSX 關聯。
		@Override
		public void markFailed(long quotationId, String relativePdfPath, String errorMessage) {
			status = "PDF_FAILED";
			this.relativePdfPath = relativePdfPath;
			this.errorMessage = errorMessage;
		}
	}
}
