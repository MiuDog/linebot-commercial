package dev.miudog.linebotcommercial.storage;

/**
 * 將供應商例外隔離在應用程式儲存邊界之外。
 */
public class ObjectStorageException extends RuntimeException {

	// 方法：執行此方法定義的受控處理流程。
	public ObjectStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
