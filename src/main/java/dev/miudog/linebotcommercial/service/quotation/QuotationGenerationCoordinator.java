package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 串接正式確認、圖片歸檔、Excel 產生及檔案狀態保存。
 */
@Service
public class QuotationGenerationCoordinator {

	private final QuotationConfirmationService confirmations;
	private final QuotationAssetArchiveService assets;
	private final QuotationWorkbookService workbooks;
	private final QuotationGenerationRepository generations;
	private final QuotationPdfService pdfs;
	private final QuotationDeliveryService deliveries;
	private QuotationProgressService progress = new QuotationProgressService();

	// 方法：建立正式報價產生協調器。
	@Autowired
	public QuotationGenerationCoordinator(
		QuotationConfirmationService confirmations,
		QuotationAssetArchiveService assets,
		QuotationWorkbookService workbooks,
		QuotationGenerationRepository generations,
		QuotationPdfService pdfs,
		QuotationDeliveryService deliveries
	) {
		this.confirmations = confirmations;
		this.assets = assets;
		this.workbooks = workbooks;
		this.generations = generations;
		this.pdfs = pdfs;
		this.deliveries = deliveries;
	}

	// 方法：保留不啟動 PDF 與 LINE 外部服務的聚焦單元測試建構介面。
	QuotationGenerationCoordinator(
		QuotationConfirmationService confirmations,
		QuotationAssetArchiveService assets,
		QuotationWorkbookService workbooks,
		QuotationGenerationRepository generations
	) {
		this(confirmations, assets, workbooks, generations, null, null);
	}

	// 方法：正式確認後歸檔圖片，以唯一選中原圖產生 Excel 並保存 READY 狀態。
	public QuotationGenerationResult generate(QuotationGenerationCommand command) {
		if (command == null || command.confirmation() == null) {
			throw new QuotationGenerationException("INVALID_GENERATION_COMMAND", "正式報價產生資料不完整", null);
		}

		QuotationConfirmationResult confirmation = confirmations.confirm(command.confirmation());
		return generateConfirmed(new QuotationConfirmedGenerationCommand(
			confirmation,
			command.confirmation().calculation(),
			command.header(),
			command.destinationId()
		));
	}

	// 方法：從 LINE 已完成確認交易的結果接續背景歸檔、Excel、PDF 與最終推播。
	public QuotationGenerationResult generateConfirmed(QuotationConfirmedGenerationCommand command) {
		if (command == null || command.confirmation() == null || command.calculation() == null) {
			throw new QuotationGenerationException("INVALID_CONFIRMED_COMMAND", "已確認報價產生資料不完整", null);
		}

		QuotationConfirmationResult confirmation = command.confirmation();
		WorkbookStage workbookStage = generateWorkbook(command);
		if (pdfs == null || deliveries == null) {
			return new QuotationGenerationResult(
				confirmation,
				workbookStage.archived(),
				workbookStage.workbook()
			);
		}

		QuotationPdfService.PdfExportResult pdf;
		try {
			pdf = pdfs.export(confirmation.quotationId());
		}
		catch (RuntimeException exception) {
			throw new QuotationGenerationException(
				pdfErrorCode(exception),
				"PDF 產生失敗，已保留 Excel 並可由管理頁重試",
				exception
			);
		}

		try {
			QuotationDeliveryResult delivery = deliveries.deliverFinal(
				confirmation.quotationId(),
				requiredDestination(command.destinationId())
			);
			return new QuotationGenerationResult(
				confirmation,
				workbookStage.archived(),
				workbookStage.workbook(),
				pdf,
				delivery
			);
		}
		catch (RuntimeException exception) {
			throw new QuotationGenerationException(
				"DELIVERY_FAILED",
				"報價 PDF 已完成，但 LINE 傳送失敗，可由管理頁重試",
				exception
			);
		}
	}

	// 方法：依持久化檔案狀態續跑未完成階段，重啟或重複排程不覆寫已完成產物。
	public void resumeConfirmed(QuotationConfirmedGenerationCommand command) {
		progress.track(command == null ? null : command.destinationId(), () -> {
			resumeStages(command);
			return null;

		}, "背景報價工作完成，請查看文件交付結果。");
	}

	// 方法：背景產檔與私人查詢共用進度，不新增主動通知。
	@org.springframework.beans.factory.annotation.Autowired
	public void configureProgress(QuotationProgressService progress) {
		this.progress = progress;
	}

	// 方法：依既有檔案狀態執行實際階段，跳過已完成的產物。
	private void resumeStages(QuotationConfirmedGenerationCommand command) {
		if (command == null || command.confirmation() == null || command.calculation() == null) {
			throw new QuotationGenerationException("INVALID_CONFIRMED_COMMAND", "已確認報價產生資料不完整", null);
		}

		long quotationId = command.confirmation().quotationId();
		QuotationGenerationRepository.GenerationStage stage = generations.stage(quotationId);
		if (stage.sent()) return;

		if (!stage.xlsxReady()) {
			progress.update("正在歸檔圖片與產生Excel報價。");
			generateWorkbook(command);
			stage = generations.stage(quotationId);
		}
		if (pdfs == null || deliveries == null) return;

		if (!stage.pdfReady()) {
			progress.update("Excel已完成，正在轉換PDF。");
			pdfs.export(quotationId);
		}

		progress.update("文件已完成，正在交付下載連結。");
		QuotationDeliveryResult delivery = deliveries.deliverFinal(
			quotationId,
			requiredDestination(command.destinationId())
		);
		if (delivery.status() != QuotationDeliveryStatus.SENT) {
			throw new QuotationGenerationException(
				"DELIVERY_NOT_COMPLETED",
				"LINE 報價交付尚未完成，背景工作將稍後重試",
				null
			);
		}
	}

	// 方法：只在圖片歸檔或 Excel 產生失敗時標記 XLSX 與報價產生失敗。
	private WorkbookStage generateWorkbook(QuotationConfirmedGenerationCommand command) {
		QuotationConfirmationResult confirmation = command.confirmation();
		QuotationArchivedAssets archived = null;
		try {
			archived = assets.archive(confirmation);
			generations.markGenerating(confirmation.quotationId());
			QuotationWorkbookService.GenerationResult workbook = workbooks.generateConfirmed(
				formalHeader(command.header(), confirmation),
				confirmation,
				command.calculation(),
				archived.selectedImagePath()
			);
			generations.markReady(confirmation.quotationId(), workbook.path());
			return new WorkbookStage(archived, workbook);
		}
		catch (RuntimeException exception) {
			String code = workbookErrorCode(exception);
			try {
				generations.markFailed(confirmation.quotationId(), code);
			}
			catch (RuntimeException statusFailure) {
				exception.addSuppressed(statusFailure);
			}
			throw new QuotationGenerationException(code, "正式報價檔案產生失敗，可由管理頁重試", exception);
		}
		finally {
			assets.cleanupTemporary(archived);
		}
	}

	// 方法：沿交易與框架包裝的原因鏈保留最接近失敗來源的穩定錯誤代碼。
	private String workbookErrorCode(RuntimeException exception) {
		Throwable cause = exception;
		for (int depth = 0; cause != null && depth < 20; depth++) {
			if (cause instanceof QuotationAssetArchiveException archiveException) return archiveException.code();

			if (cause instanceof QuotationGenerationException generationException) return generationException.code();

			cause = cause.getCause();
		}
		return "WORKBOOK_GENERATION_FAILED";
	}

	// 方法：從 PDF 管理例外取得可安全記錄的穩定代碼。
	private String pdfErrorCode(RuntimeException exception) {
		return exception instanceof QuotationAdminException adminException
			? adminException.code()
			: "PDF_EXPORT_FAILED";
	}

	// 方法：要求背景最終交付保留原始一對一 LINE 使用者目的地。
	private String requiredDestination(String destinationId) {
		if (destinationId == null || destinationId.isBlank()) {
			throw new QuotationGenerationException("MISSING_DELIVERY_DESTINATION", "缺少 LINE 最終交付對象", null);
		}
		return destinationId;
	}

	// 方法：以正式流水號與日期覆蓋不可由草稿預先決定的報表欄位。
	private QuotationWorkbookService.Header formalHeader(
		QuotationWorkbookService.Header source,
		QuotationConfirmationResult confirmation
	) {
		QuotationWorkbookService.Header safe = source == null
			? new QuotationWorkbookService.Header("", "", "", "", "", "", "", "", "")
			: source;
		return new QuotationWorkbookService.Header(
			confirmation.quotationNumber(),
			confirmation.quotationDate().toString(),
			safe.customerName(),
			safe.phoneFax(),
			safe.customerEmail(),
			safe.contact(),
			safe.projectSite(),
			safe.salesRepresentative(),
			confirmation.validUntil().toString()
		);
	}

	private record WorkbookStage(
		QuotationArchivedAssets archived,
		QuotationWorkbookService.GenerationResult workbook
	) {}
}
