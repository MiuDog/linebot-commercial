package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.Optional;

/**
 * 保存 PDF 產生狀態；實作不得重新配置報價流水號或輸出資料夾。
 */
public interface QuotationPdfStore {

	// 方法：讀取既有報價與已完成 XLSX 的固定工作資料。
	Optional<PdfJob> find(long quotationId);

	// 方法：把既有報價與 PDF 檔案列標記為產生中。
	void markGenerating(long quotationId, String relativePdfPath);

	// 方法：保存已驗證 PDF 的相對路徑、雜湊與大小。
	void markReady(long quotationId, String relativePdfPath, String contentHash, long fileSize);

	// 方法：保存失敗摘要並將報價標記為 PDF_FAILED。
	void markFailed(long quotationId, String relativePdfPath, String errorMessage);

	record PdfJob(long quotationId, String quotationNumber, String status, String xlsxRelativePath) {}
}
