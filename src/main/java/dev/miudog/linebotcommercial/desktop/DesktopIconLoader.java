package dev.miudog.linebotcommercial.desktop;

import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集中載入 commercial 桌面品牌圖示，供主視窗與系統匣共用。
 */
public final class DesktopIconLoader {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(DesktopIconLoader.class);
	private static final String ICON_RESOURCE = "/desktop/app-icon.png";
	private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
	private static final Image ICON = loadIcon();
	private static final List<Image> ICONS = createIconSizes();

	//#endregion

	//#region 建構子

	// 方法：禁止建立僅提供靜態品牌圖示的工具類別。
	private DesktopIconLoader() {
	}

	//#endregion

	//#region 方法

	// 方法：取得已快取的 commercial 品牌圖示，避免每次顯示視窗時重複解碼。
	public static Image load() {
		return ICON;
	}

	// 方法：取得 Windows 視窗與工作列所需的完整多尺寸品牌圖示。
	public static List<Image> loadAll() {
		return ICONS;
	}

	// 方法：在目前作業系統支援時明確設定應用程式工作列品牌圖示。
	public static void applyTaskbarIcon() {
		if (!Taskbar.isTaskbarSupported()) return;

		try {
			// AWT 函式庫：取得系統工作列介面並確認可設定應用程式圖示。
			Taskbar taskbar = Taskbar.getTaskbar();
			if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return;

			// AWT 函式庫：以原始品牌素材覆蓋 Java 執行環境的預設工作列圖示。
			taskbar.setIconImage(ICON);
		}
		catch (SecurityException | UnsupportedOperationException exception) {

			// 日誌：記錄作業系統拒絕工作列品牌圖示，但不影響應用程式啟動。
			log.warn("event=desktop_taskbar_icon_failed errorType={}", exception.getClass().getSimpleName());
		}
	}

	// 方法：建立 Windows 各種顯示比例所需的多尺寸品牌圖示快取。
	private static List<Image> createIconSizes() {
		List<Image> icons = new ArrayList<>();

		// 圖片函式庫：逐一產生工作列、標題列與視窗切換器常用尺寸。
		for (int size : ICON_SIZES) {
			icons.add(resizeIcon(size));
		}

		return List.copyOf(icons);
	}

	// 方法：以高品質插值將原始品牌圖示縮放為指定正方形尺寸。
	private static Image resizeIcon(int size) {
		BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

		// AWT 圖片函式庫：以雙三次插值保留小尺寸圖示的輪廓與透明邊緣。
		Graphics2D graphics = resized.createGraphics();

		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(ICON, 0, 0, size, size, null);
		}
		finally {
			graphics.dispose();
		}

		return resized;
	}

	// 方法：從應用程式資源讀取品牌圖示，缺檔或解碼失敗時提供透明安全後備。
	private static Image loadIcon() {

		// Java 資源函式庫：從封裝後的 classpath 開啟品牌 PNG 並在區塊結束時關閉串流。
		try (InputStream input = DesktopIconLoader.class.getResourceAsStream(ICON_RESOURCE)) {
			if (input == null) {

				// 日誌：指出封裝遺漏圖示資源，方便從啟動紀錄追查品牌素材問題。
				log.warn("event=desktop_icon_missing resource={}", ICON_RESOURCE);

				return fallbackIcon();
			}

			// 圖片函式庫：將內嵌 PNG 解碼成 AWT 可直接供視窗與系統匣使用的影像。
			BufferedImage image = ImageIO.read(input);
			if (image != null) return image;

			// 日誌：指出圖示內容無法被圖片函式庫辨識，避免靜默使用錯誤素材。
			log.warn("event=desktop_icon_decode_failed resource={}", ICON_RESOURCE);
		}
		catch (IOException exception) {

			// 日誌：記錄圖示載入失敗類型，但不阻止主程式啟動。
			log.warn("event=desktop_icon_load_failed resource={} errorType={}", ICON_RESOURCE, exception.getClass().getSimpleName());
		}

		return fallbackIcon();
	}

	// 方法：建立透明安全後備圖示，確保品牌素材異常不會中斷桌面程式。
	private static Image fallbackIcon() {
		return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
	}

	//#endregion
}
