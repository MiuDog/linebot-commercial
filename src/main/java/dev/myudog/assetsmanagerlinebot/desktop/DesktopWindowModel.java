package dev.myudog.assetsmanagerlinebot.desktop;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 集中管理桌面視窗與系統匣需要顯示的狀態及網址。
 */
public final class DesktopWindowModel {

	//#region 欄位

	private final List<Consumer<DesktopWindowSnapshot>> listeners;
	private String localUrl;
	private DesktopStatus status;
	private String publicUrl;

	//#endregion

	//#region 建構子

	// 方法：以本機服務 Port 建立尚未啟動的視窗模型。
	public DesktopWindowModel(int port) {
		if (port < 1 || port > 65535) throw new IllegalArgumentException("本機服務 Port 無效");

		this.listeners = new CopyOnWriteArrayList<>();
		this.localUrl = "http://127.0.0.1:" + port;
		this.status = DesktopStatus.STOPPED;
		this.publicUrl = "未啟用";
	}

	//#endregion

	//#region 方法

	// 方法：更新後端狀態並發布完整視窗快照。
	public synchronized void updateStatus(DesktopStatus status) {
		this.status = Objects.requireNonNull(status, "桌面狀態不可為 null");
		publish();
	}

	// 方法：更新 ngrok 或其他 HTTPS 公開網址並發布完整快照。
	public synchronized void updatePublicUrl(String publicUrl) {
		this.publicUrl = publicUrl == null || publicUrl.isBlank() ? "未啟用" : publicUrl;
		publish();
	}

	// 方法：設定變更後更新本機服務 Port 並發布完整快照。
	public synchronized void updatePort(int port) {
		if (port < 1 || port > 65535) throw new IllegalArgumentException("本機服務 Port 無效");

		this.localUrl = "http://127.0.0.1:" + port;
		publish();
	}

	// 方法：取得目前完整且不可變的顯示快照。
	public synchronized DesktopWindowSnapshot snapshot() {
		String callbackUrl = publicUrl.startsWith("http") ? publicUrl + "/callback" : "未啟用";

		return new DesktopWindowSnapshot(status, statusText(status), localUrl, publicUrl, callbackUrl);
	}

	// 方法：加入視窗或系統匣顯示狀態監聽器。
	public void addListener(Consumer<DesktopWindowSnapshot> listener) {
		listeners.add(Objects.requireNonNull(listener, "視窗狀態監聽器不可為 null"));
	}

	// 方法：發布單一時間點的完整快照，避免元件讀到混合狀態。
	private void publish() {
		DesktopWindowSnapshot snapshot = snapshot();

		for (Consumer<DesktopWindowSnapshot> listener : listeners) {
			listener.accept(snapshot);
		}
	}

	// 方法：將後端狀態轉換為非技術使用者可理解的繁體中文。
	private String statusText(DesktopStatus status) {
		return switch (status) {
			case STOPPED -> "尚未啟動";
			case STARTING -> "啟動中";
			case RUNNING -> "已啟動，正在背景執行";
			case STOPPING -> "停止中";
			case FAILED -> "啟動失敗，請查看記錄";
		};
	}

	//#endregion
}
