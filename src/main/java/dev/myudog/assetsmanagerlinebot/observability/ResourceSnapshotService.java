package dev.myudog.assetsmanagerlinebot.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** 定期輸出低基數的 JVM、執行緒與日誌磁碟 USE 資源快照。 */
@Component
public class ResourceSnapshotService {

	private static final Logger log = LoggerFactory.getLogger(ResourceSnapshotService.class);

	private final Path logPath;

	// 方法：以設定的日誌目錄作為磁碟容量觀測目標。
	@Autowired
	public ResourceSnapshotService(@Value("${app.observability.log-path:${user.dir}/log}") String logPath) {
		this(Path.of(logPath));
	}

	// 方法：提供測試以明確路徑建立資源觀測器。
	ResourceSnapshotService(Path logPath) {
		this.logPath = logPath.toAbsolutePath().normalize();
	}

	// 方法：程式啟動完成後立即輸出第一份資源快照。
	@EventListener(ApplicationReadyEvent.class)
	public void logAfterStartup() {
		logSnapshot();
	}

	// 方法：依設定週期輸出資源快照，預設每分鐘一次。
	@Scheduled(
		fixedDelayString = "${app.observability.resource-interval-ms:60000}",
		initialDelayString = "${app.observability.resource-interval-ms:60000}"
	)
	public void logPeriodically() {
		logSnapshot();
	}

	// 方法：蒐集不含程序參數、環境變數或檔名的有限資源指標。
	public ResourceSnapshot snapshot() {
		// 使用 JVM 管理介面取得記憶體與執行緒統計。
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
		int liveThreads = ManagementFactory.getThreadMXBean().getThreadCount();
		int peakThreads = ManagementFactory.getThreadMXBean().getPeakThreadCount();
		long[] disk = diskCapacity();
		return new ResourceSnapshot(
			heap.getUsed(),
			heap.getCommitted(),
			nonHeap.getUsed(),
			liveThreads,
			peakThreads,
			disk[0],
			disk[1]
		);
	}

	// 方法：寫入一筆具 correlation ID 的結構化資源事件。
	private void logSnapshot() {
		boolean ownsRequestId = MDC.get("requestId") == null;
		if (ownsRequestId) MDC.put("requestId", "resource-monitor");

		try {
			ResourceSnapshot snapshot = snapshot();
			log.atInfo()
				.addKeyValue("event", "resource_snapshot")
				.addKeyValue("requestId", MDC.get("requestId"))
				.addKeyValue("heapUsedBytes", snapshot.heapUsedBytes())
				.addKeyValue("heapCommittedBytes", snapshot.heapCommittedBytes())
				.addKeyValue("nonHeapUsedBytes", snapshot.nonHeapUsedBytes())
				.addKeyValue("liveThreadCount", snapshot.liveThreadCount())
				.addKeyValue("peakThreadCount", snapshot.peakThreadCount())
				.addKeyValue("diskTotalBytes", snapshot.diskTotalBytes())
				.addKeyValue("diskUsableBytes", snapshot.diskUsableBytes())
				.log(
					"event=resource_snapshot requestId={} heapUsedBytes={} heapCommittedBytes={} "
						+ "nonHeapUsedBytes={} liveThreadCount={} peakThreadCount={} "
						+ "diskTotalBytes={} diskUsableBytes={}",
					MDC.get("requestId"),
					snapshot.heapUsedBytes(),
					snapshot.heapCommittedBytes(),
					snapshot.nonHeapUsedBytes(),
					snapshot.liveThreadCount(),
					snapshot.peakThreadCount(),
					snapshot.diskTotalBytes(),
					snapshot.diskUsableBytes()
				);
		}
		finally {
			if (ownsRequestId) MDC.remove("requestId");
		}
	}

	// 方法：取得日誌所在磁碟總量與可用量；觀測失敗時以零值呈現，不影響主流程。
	private long[] diskCapacity() {
		try {
			Path existingPath = nearestExistingPath(logPath);

			// 使用 NIO FileStore 讀取同一磁碟分割區容量。
			FileStore store = Files.getFileStore(existingPath);
			return new long[]{store.getTotalSpace(), store.getUsableSpace()};
		}
		catch (IOException exception) {
			return new long[]{0, 0};
		}
	}

	// 方法：從尚未建立的 log 目錄向上尋找最近存在路徑。
	private static Path nearestExistingPath(Path path) {
		Path candidate = path;

		// 外部檔案系統：只檢查路徑是否存在，不列舉檔名或讀取檔案內容。
		while (candidate != null && !Files.exists(candidate)) {
			candidate = candidate.getParent();
		}
		return candidate == null ? Path.of(".").toAbsolutePath().normalize() : candidate;
	}
}
