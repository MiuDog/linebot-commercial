package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSnapshotServiceTest {

	// 驗證資源快照可取得 JVM、執行緒與日誌磁碟容量，不包含環境機密。
	@Test
	void capturesBoundedJvmThreadAndDiskMetrics() {
		ResourceSnapshotService service = new ResourceSnapshotService(Path.of("."));

		ResourceSnapshot snapshot = service.snapshot();

		assertThat(snapshot.heapUsedBytes()).isGreaterThanOrEqualTo(0);
		assertThat(snapshot.heapCommittedBytes()).isGreaterThan(0);
		assertThat(snapshot.liveThreadCount()).isGreaterThan(0);
		assertThat(snapshot.diskTotalBytes()).isGreaterThan(0);
		assertThat(snapshot.diskUsableBytes()).isGreaterThanOrEqualTo(0);
	}
}
