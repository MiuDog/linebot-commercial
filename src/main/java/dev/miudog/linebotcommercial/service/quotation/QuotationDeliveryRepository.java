package dev.miudog.linebotcommercial.service.quotation;

public interface QuotationDeliveryRepository {

	// 方法：讀取可交付 PDF 所屬的正式報價金額快照。
	QuotationDeliverySnapshot findSnapshot(long quotationId);

	// 方法：以資料庫宣告最終 LINE 交付權並回傳累積嘗試次數。
	QuotationDeliveryClaim claimFinal(long quotationId, String destinationId);

	// 方法：保存 LINE 成功回應與供應商訊息識別碼。
	void markSent(long attemptId, String providerMessageId);

	// 方法：保存可安全顯示且不含個資的錯誤摘要。
	void markFailed(long attemptId, String errorSummary);
}
