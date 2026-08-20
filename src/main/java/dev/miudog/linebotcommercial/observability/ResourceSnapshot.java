package dev.miudog.linebotcommercial.observability;

/** 單次 JVM、執行緒與日誌磁碟資源快照。 */
public record ResourceSnapshot(
	long heapUsedBytes,
	long heapCommittedBytes,
	long nonHeapUsedBytes,
	int liveThreadCount,
	int peakThreadCount,
	long diskTotalBytes,
	long diskUsableBytes
) {}
