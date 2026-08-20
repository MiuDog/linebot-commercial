package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 限制 Microsoft Excel COM 報價工作只能單工執行，並以有限佇列提供背壓。
 */
@Configuration
public class QuotationGenerationExecutorConfiguration {

	// 方法：建立單一工作者與有限等待佇列的正式報價執行器。
	@Bean(name = "quotationGenerationTaskExecutor")
	public ThreadPoolTaskExecutor quotationGenerationTaskExecutor(
		@Value("${app.quotation.generation-queue-capacity:20}") int queueCapacity
	) {
		if (queueCapacity < 0 || queueCapacity > 1000) {
			throw new IllegalArgumentException("報價產生佇列容量必須介於 0 至 1000");
		}

		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("quotation-generation-");
		executor.setTaskDecorator(task -> decoratedTask(task, MDC.getCopyOfContextMap()));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	// 方法：在背景工作期間套用提交執行緒 MDC，完成後還原並清除工作者殘留資料。
	private Runnable decoratedTask(Runnable task, Map<String, String> capturedContext) {
		return () -> {
			Map<String, String> workerContext = MDC.getCopyOfContextMap();
			try {
				if (capturedContext == null) MDC.clear();
				else MDC.setContextMap(capturedContext);

				task.run();
			}
			finally {
				MDC.clear();
				if (workerContext != null) MDC.setContextMap(workerContext);
			}
		};
	}
}
