package dev.miudog.linebotcommercial.desktop.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證同一台電腦只能有一個 App 持有指定 Cloudflare Tunnel。
 */
class CloudflareTunnelLeaseTest {

	@TempDir
	Path temporaryDirectory;

	//#region 測試

	// 方法：第二個 App 無法占用相同 Tunnel，原持有者釋放後才可接手。
	@Test
	void shouldPreventTwoLocalAppsFromUsingTheSameTunnel() {
		UUID tunnelId = UUID.fromString("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");
		CloudflareTunnelLease commercialLease = new CloudflareTunnelLease(temporaryDirectory);
		CloudflareTunnelLease documentLease = new CloudflareTunnelLease(temporaryDirectory);

		assertThat(commercialLease.acquire(tunnelId, "LinebotCommercial")).isTrue();
		assertThat(documentLease.acquire(tunnelId, "LinebotDocument")).isFalse();

		commercialLease.release();

		assertThat(documentLease.acquire(tunnelId, "LinebotDocument")).isTrue();
		documentLease.release();
	}

	//#endregion
}
