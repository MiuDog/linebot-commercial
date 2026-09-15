package dev.miudog.linebotcommercial.storage;

import java.util.Map;

/**
 * 公司資產使用的可攜式物件儲存邊界。
 */
public interface ObjectStorage {

	// 方法：執行此方法定義的受控處理流程。
	StoredObject put(String relativeKey, byte[] content, String contentType, Map<String, String> metadata);

	// 方法：執行此方法定義的受控處理流程。
	byte[] get(String relativeKey);

	// 方法：執行此方法定義的受控處理流程。
	StoredObject metadata(String relativeKey);

	// 方法：執行此方法定義的受控處理流程。
	void copy(String sourceRelativeKey, String targetRelativeKey);

	// 方法：執行此方法定義的受控處理流程。
	void delete(String relativeKey);
}
