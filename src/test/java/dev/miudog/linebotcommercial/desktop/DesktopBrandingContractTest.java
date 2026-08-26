package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Image;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 驗證 commercial 桌面程式在執行期與 Windows 封裝使用同一組品牌圖示。
 */
class DesktopBrandingContractTest {

	//#region 測試

	// 方法：確認主視窗、系統匣、執行檔與安裝器都引用 commercial 品牌圖示。
	@Test
	void usesCommercialIconAcrossDesktopAndWindowsPackaging() throws Exception {

		// Java 路徑函式庫：定位實際產品素材與封裝腳本，避免驗證測試副本。
		Path projectRoot = Path.of("").toAbsolutePath();
		Path runtimeIcon = projectRoot.resolve("src/main/resources/desktop/app-icon.png");
		Path windowsIcon = projectRoot.resolve("packaging/windows/app-icon.ico");
		String traySource = read(projectRoot.resolve("src/main/java/dev/miudog/linebotcommercial/desktop/AwtTrayAccess.java"));
		String windowSource = read(projectRoot.resolve("src/main/java/dev/miudog/linebotcommercial/desktop/DesktopWindow.java"));
		String packageScript = read(projectRoot.resolve("scripts/package-windows-app.ps1"));
		String installerScript = read(projectRoot.resolve("packaging/windows/installer.nsi"));

		// AssertJ 函式庫：驗證品牌素材存在且所有 Windows 與桌面入口均已接線。
		assertThat(runtimeIcon).isRegularFile();
		assertThat(windowsIcon).isRegularFile();
		assertThat(traySource).contains("DesktopIconLoader.load()");
		assertThat(traySource).contains("JPopupMenu", "JMenuItem");
		assertThat(traySource).doesNotContain("java.awt.PopupMenu", "java.awt.MenuItem");
		assertThat(windowSource).contains("frame.setIconImages(DesktopIconLoader.loadAll())");
		assertThat(windowSource).contains("DesktopIconLoader.applyTaskbarIcon()");
		assertThat(packageScript).contains("\"--icon\", $AppIconPath");
		assertThat(installerScript).contains("!define MUI_ICON \"${APP_ICON}\"");
		assertThat(installerScript).contains("!define MUI_UNICON \"${APP_ICON}\"");
		assertThat(installerScript).contains("\"\" \"$INSTDIR\\${PRODUCT_NAME}.exe\" 0");
	}

	// 方法：確認應用程式可解碼完整透明品牌圖示，而非落入空白安全後備。
	@Test
	void loadsCompleteTransparentRuntimeIcon() {

		// AWT 圖片函式庫：載入與正式桌面視窗完全相同的快取品牌圖示。
		Image icon = DesktopIconLoader.load();

		// AssertJ 函式庫：以原始尺寸辨識成功解碼的使用者指定素材。
		assertThat(icon.getWidth(null)).isEqualTo(256);
		assertThat(icon.getHeight(null)).isEqualTo(256);
	}

	// 方法：確認 Windows 可取得完整多尺寸圖示，避免工作列回退成 Java 預設圖示。
	@Test
	void loadsWindowsRuntimeIconSizes() {

		// AWT 圖片函式庫：取得主視窗與工作列實際共用的多尺寸品牌圖示。
		List<Image> icons = DesktopIconLoader.loadAll();

		// AssertJ 函式庫：驗證 Windows 常用的視窗與工作列尺寸均已備妥。
		assertThat(icons)
			.extracting(image -> image.getWidth(null))
			.containsExactly(16, 20, 24, 32, 40, 48, 64, 128, 256);
	}

	// 方法：以 UTF-8 讀取受驗證的品牌整合檔案。
	private String read(Path path) throws Exception {

		// 外部函式：讀取實際封裝與桌面原始碼，避免測試只驗證複製出的假資料。
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	//#endregion
}
