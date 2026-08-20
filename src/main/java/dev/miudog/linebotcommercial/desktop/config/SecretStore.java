package dev.miudog.linebotcommercial.desktop.config;

/**
 * 定義機密資料與平台保護機制之間的隔離邊界。
 */
public interface SecretStore {

	// 方法：將明文轉成只能由指定平台身分解密的資料。
	byte[] protect(byte[] plaintext);

	// 方法：將受保護資料還原為明文。
	byte[] unprotect(byte[] protectedData);

	// 方法：判斷目前平台是否能使用這個保護機制。
	boolean available();
}

