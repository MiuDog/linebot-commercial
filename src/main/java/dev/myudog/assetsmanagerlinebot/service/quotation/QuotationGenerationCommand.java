package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 正式確認、圖片歸檔及 Excel 產生所需的單一應用命令。
 */
public record QuotationGenerationCommand(
	QuotationConfirmationCommand confirmation,
	QuotationWorkbookService.Header header,
	String destinationId
) {

	// 方法：保留不需立即 LINE 交付的管理端產生介面。
	public QuotationGenerationCommand(
		QuotationConfirmationCommand confirmation,
		QuotationWorkbookService.Header header
	) {
		this(confirmation, header, null);
	}
}
