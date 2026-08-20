package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 正式報價確認、資產與 Excel 產出的完整結果。
 */
public record QuotationGenerationResult(
	QuotationConfirmationResult confirmation,
	QuotationArchivedAssets archivedAssets,
	QuotationWorkbookService.GenerationResult workbook,
	QuotationPdfService.PdfExportResult pdf,
	QuotationDeliveryResult delivery
) {

	// 方法：保留只產生 Excel 的測試與管理端結果介面。
	public QuotationGenerationResult(
		QuotationConfirmationResult confirmation,
		QuotationArchivedAssets archivedAssets,
		QuotationWorkbookService.GenerationResult workbook
	) {
		this(confirmation, archivedAssets, workbook, null, null);
	}
}
