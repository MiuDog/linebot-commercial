package dev.miudog.linebotcommercial.service.quotation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationGenerationExecutorConfigurationTest {

	private ThreadPoolTaskExecutor executor;

	@AfterEach
	void cleanUp() {
		MDC.clear();
		if (executor != null) executor.shutdown();
	}

	@Test
	void propagatesEachSubmittingRequestIdWithoutLeakingWorkerContext() throws Exception {
		executor = new QuotationGenerationExecutorConfiguration().quotationGenerationTaskExecutor(4);
		executor.initialize();
		AtomicReference<String> firstRequestId = new AtomicReference<>();
		AtomicReference<String> secondRequestId = new AtomicReference<>();
		AtomicReference<String> workerLeak = new AtomicReference<>();

		MDC.put("requestId", "request-first");
		runTask(() -> {
			firstRequestId.set(MDC.get("requestId"));
			MDC.put("workerOnly", "must-not-leak");
		});
		MDC.put("requestId", "request-second");
		runTask(() -> {
			secondRequestId.set(MDC.get("requestId"));
			workerLeak.set(MDC.get("workerOnly"));
		});

		assertThat(firstRequestId).hasValue("request-first");
		assertThat(secondRequestId).hasValue("request-second");
		assertThat(workerLeak).hasNullValue();
	}

	// 方法：提交背景工作並等待單工 executor 完成。
	private void runTask(Runnable task) throws Exception {
		CountDownLatch completed = new CountDownLatch(1);
		executor.execute(() -> {
			try {
				task.run();
			}
			finally {
				completed.countDown();
			}
		});
		assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
	}
}
