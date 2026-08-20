package dev.myudog.assetsmanagerlinebot.service.quotation;

public class UnconfiguredQuotationDraftWorkflowPort implements QuotationDraftWorkflowPort {

	// 方法：未接上持久層時不宣稱有活動草稿。
	@Override
	public boolean hasActiveDraft(String ownerId) {
		return false;
	}

	// 方法：明確阻擋尚未接上持久層的文字流程。
	@Override
	public QuotationDraftWork applyText(String ownerId, String messageId, String text) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的圖片流程。
	@Override
	public QuotationDraftWork attachImage(String ownerId, String messageId) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的報價格式設定。
	@Override
	public QuotationDraftWork applyScheme(long draftId, String ownerId, String schemeCode) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的草稿讀取。
	@Override
	public QuotationDraftWork load(long draftId, String ownerId) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的 revision 讀取。
	@Override
	public int currentRevision(long draftId, String ownerId) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的儲存。
	@Override
	public void save(QuotationDraftSnapshot draft) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的修改請求。
	@Override
	public QuotationDraftWork requestModification(QuotationDraftWork work) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的圖片更換。
	@Override
	public QuotationDraftWork selectImage(long draftId, String ownerId, String messageId) {
		throw unavailable();
	}

	// 方法：明確阻擋尚未接上持久層的圖片移除。
	@Override
	public QuotationDraftWork removeSelectedImage(long draftId, String ownerId) {
		throw unavailable();
	}

	// 方法：建立不洩漏內部資訊的未啟用例外。
	private QuotationLineWorkflowException unavailable() {
		return new QuotationLineWorkflowException("WORKFLOW_UNAVAILABLE", "報價草稿資料服務尚未啟用，請稍後再試。");
	}
}
