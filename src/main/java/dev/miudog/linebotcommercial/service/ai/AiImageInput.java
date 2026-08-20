package dev.miudog.linebotcommercial.service.ai;

/**
 * 表示送給模型的一張候選圖片及其穩定訊息代碼。
 */
public record AiImageInput(String messageId, byte[] bytes, String contentType) {

	// 方法：建立圖片輸入並複製位元組，避免呼叫端在請求期間竄改內容。
	public AiImageInput {
		bytes = bytes == null ? null : bytes.clone();
	}

	// 方法：回傳圖片位元組副本，避免外部修改內部內容。
	@Override
	public byte[] bytes() {
		return bytes == null ? null : bytes.clone();
	}
}
