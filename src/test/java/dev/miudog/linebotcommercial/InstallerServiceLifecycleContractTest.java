package dev.miudog.linebotcommercial;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 驗證 Setup 會管理目前使用者背景 service 的自動啟動、升級與解除安裝。
 */
class InstallerServiceLifecycleContractTest {

	// 方法：確認 Installer 註冊自動啟動、升級後恢復 service，解除安裝前停止並移除註冊。
	@Test
	void shouldManagePerUserServiceAcrossInstallerLifecycle() throws IOException {
		Path installerPath = Path.of("packaging", "windows", "installer.nsi");

		// 外部檔案：讀取實際 NSIS 發布來源，避免只驗證測試替身。
		String installer = Files.readString(installerPath);

		assertThat(installer).contains("!define SERVICE_NAME \"LinebotCommercialService\"");
		assertThat(installer).contains("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
		assertThat(installer).contains("WriteRegStr HKCU \"${RUN_KEY}\"");
		assertThat(installer).contains("DeleteRegValue HKCU \"${RUN_KEY}\"");
		assertThat(installer).contains("Exec '\"$INSTDIR\\${SERVICE_NAME}.exe\"'");
		assertThat(installer).contains("ExecWait '\"$INSTDIR\\${PRODUCT_NAME}.exe\" --shutdown'");
	}
}
