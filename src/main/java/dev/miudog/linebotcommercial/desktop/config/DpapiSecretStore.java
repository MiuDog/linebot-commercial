package dev.miudog.linebotcommercial.desktop.config;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.util.Objects;

/**
 * 以 Windows DPAPI 保護目前使用者的桌面設定機密。
 */
public final class DpapiSecretStore implements SecretStore {

	//#region 方法

	// 方法：以目前 Windows 使用者的 DPAPI 保護明文資料。
	@Override
	public byte[] protect(byte[] plaintext) {
		Objects.requireNonNull(plaintext, "待加密資料不可為 null");
		assertAvailable();

		try {
			// 外部 API：使用 JNA 官方 Crypt32Util mapping，禁止 DPAPI 顯示互動視窗。
			// 來源：https://java-native-access.github.io/jna/4.2.1/com/sun/jna/platform/win32/Crypt32Util.html
			return Crypt32Util.cryptProtectData(plaintext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
		}
		catch (RuntimeException exception) {
			throw new SecretProtectionException("無法加密目前使用者的機密設定", exception);
		}
	}

	// 方法：以目前 Windows 使用者的 DPAPI 還原受保護資料。
	@Override
	public byte[] unprotect(byte[] protectedData) {
		Objects.requireNonNull(protectedData, "待解密資料不可為 null");
		assertAvailable();

		try {
			// 外部 API：使用 JNA 官方 Crypt32Util mapping 驗證完整性並解密資料。
			// 來源：https://java-native-access.github.io/jna/4.2.1/com/sun/jna/platform/win32/Crypt32Util.html
			return Crypt32Util.cryptUnprotectData(protectedData, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
		}
		catch (RuntimeException exception) {
			throw new SecretProtectionException("無法解密目前使用者的機密設定", exception);
		}
	}

	// 方法：判斷目前執行環境是否為 Windows。
	@Override
	public boolean available() {
		return Platform.isWindows();
	}

	// 方法：阻止非 Windows 平台意外載入 DPAPI native 呼叫。
	private void assertAvailable() {
		if (!available()) throw new SecretProtectionException("目前平台不支援 Windows DPAPI", null);
	}

	//#endregion
}

