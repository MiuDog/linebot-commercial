package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 已配置正式流水號後交給背景工作者的不可變產生命令。
 */
public record QuotationConfirmedGenerationCommand(
	QuotationConfirmationResult confirmation,
	QuotationCalculationResult calculation,
	QuotationWorkbookService.Header header,
	String destinationId,
	String additionalHeader
) {

	// 方法：保留既有呼叫端的相容建構式，未提供其他抬頭時使用空字串。
	public QuotationConfirmedGenerationCommand(
		QuotationConfirmationResult confirmation,
		QuotationCalculationResult calculation,
		QuotationWorkbookService.Header header,
		String destinationId
	) {
		this(confirmation, calculation, header, destinationId, "");
	}
}
