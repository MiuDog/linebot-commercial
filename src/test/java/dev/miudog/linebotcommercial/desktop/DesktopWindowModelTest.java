package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareAgentIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面主視窗模型的狀態、網址與監聽通知。
 */
class DesktopWindowModelTest {

	// 方法：服務狀態與公開網址變更時發布完整不可變快照。
	@Test
	void shouldPublishCompleteSnapshotsWhenStateChanges() {
		DesktopWindowModel model = new DesktopWindowModel(8088);
		List<DesktopWindowSnapshot> snapshots = new ArrayList<>();
		model.addListener(snapshots::add);

		model.updateStatus(DesktopStatus.RUNNING);
		model.updatePublicUrl("https://example.ngrok.app");

		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.getLast().status()).isEqualTo(DesktopStatus.RUNNING);
		assertThat(snapshots.getLast().localUrl()).isEqualTo("http://127.0.0.1:8088/admin");
		assertThat(snapshots.getLast().publicUrl()).isEqualTo("https://example.ngrok.app");
	}

	// 方法：各生命週期狀態都映射為非技術使用者可理解的繁體中文文字。
	@Test
	void shouldDescribeEveryLifecycleStatusInTraditionalChinese() {
		DesktopWindowModel model = new DesktopWindowModel(8088);

		for (DesktopStatus status : DesktopStatus.values()) {
			model.updateStatus(status);

			assertThat(model.snapshot().statusText()).isNotBlank();
		}
	}

	// 方法：Cloudflare 啟用後在狀態快照顯示 Tunnel、Connector 與目前電腦身分。
	@Test
	void shouldPublishCloudflareConnectorIdentity() {
		DesktopWindowModel model = new DesktopWindowModel(8088);

		model.updateCloudflareIdentity(new CloudflareAgentIdentity(
			"b5b327f7-ead7-449c-b5eb-97fc74fccbfb",
			"58cc3df9-a8f2-41e7-831a-8e699240eb25",
			"OFFICE-PC"
		));

		assertThat(model.snapshot().cloudflareIdentity())
			.contains("OFFICE-PC")
			.contains("b5b327f7")
			.contains("58cc3df9");
	}
}
