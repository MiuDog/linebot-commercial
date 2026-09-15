package dev.miudog.linebotcommercial.storage;

/**
 * 寫入或查詢物件後回傳的不可變識別資料。
 */
public record StoredObject(
	String key,
	String versionId,
	String eTag,
	String sha256,
	long contentLength,
	String contentType
) {
}
