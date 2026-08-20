package dev.miudog.linebotcommercial.service.quotation;

public interface QuotationDraftWorkflowPort {

	// 方法：查詢使用者是否有尚未結束的報價草稿。
	boolean hasActiveDraft(String ownerId);

	// 方法：解析文字並合併至草稿，但不得依 AI 的 nextAction 執行動作。
	QuotationDraftWork applyText(String ownerId, String messageId, String text);

	// 方法：解析文字與被引用的名片／案場圖片，供 OCR 與候選評分使用。
	default QuotationDraftWork applyText(
		String ownerId,
		String messageId,
		String text,
		String quotedImageMessageId
	) {
		return applyText(ownerId, messageId, text);
	}

	// 方法：把已暫存的 LINE 圖片掛入使用者的草稿。
	QuotationDraftWork attachImage(String ownerId, String messageId);

	// 方法：把已收齊的 LINE 圖片組一次加入草稿，確保 AI 在全部候選中只選一張。
	default QuotationDraftWork attachImages(String ownerId, java.util.List<String> messageIds) {
		if (messageIds == null || messageIds.size() != 1) {
			throw new UnsupportedOperationException("此草稿儲存介面尚不支援整組圖片");
		}

		return attachImage(ownerId, messageIds.getFirst());
	}

	// 方法：依草稿與擁有者讀取工作內容。
	QuotationDraftWork load(long draftId, String ownerId);

	// 方法：讀取草稿目前 revision，供簽章驗證。
	int currentRevision(long draftId, String ownerId);

	// 方法：儲存經應用層狀態機核准的草稿快照。
	void save(QuotationDraftSnapshot draft);

	// 方法：將草稿切換回可接受修改內容的狀態。
	QuotationDraftWork requestModification(QuotationDraftWork work);

	// 方法：套用使用者指定的報價格式；格式只能在尚未指定時設定一次。
	QuotationDraftWork applyScheme(long draftId, String ownerId, String schemeCode);

	// 方法：更換草稿中要嵌入報價的候選圖片。
	QuotationDraftWork selectImage(long draftId, String ownerId, String messageId);

	// 方法：移除目前選取圖片，但保留全部 pending 原圖候選。
	QuotationDraftWork removeSelectedImage(long draftId, String ownerId);
}
