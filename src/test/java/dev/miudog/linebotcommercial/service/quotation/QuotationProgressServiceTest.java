package dev.miudog.linebotcommercial.service.quotation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationProgressServiceTest {
	// 方法：執行中可從另一執行緒查詢，且不能讀取另一使用者的進度。
	@Test
	void exposesOnlyOwnersLiveProgressAndClearsThreadContext() throws Exception {
		var service = new QuotationProgressService();
		var started = new CountDownLatch(1);
		var finish = new CountDownLatch(1);
		var worker = CompletableFuture.runAsync(() -> service.track("owner-a", () -> {
			service.update("正在驗證欄位");
			started.countDown();
			try {
				assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			return "done";

		}));
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(service.status("owner-a")).contains("正在驗證欄位");
			assertThat(service.status("owner-b")).doesNotContain("正在驗證欄位");
		}
		finally {
			finish.countDown();
		}
		worker.get(5, TimeUnit.SECONDS);
		service.update("不得污染其他事件");
		assertThat(service.status("owner-a")).contains("處理完成").doesNotContain("不得污染");
	}

	// 方法：進度只保留穩定錯誤代碼，不洩漏供應商原文或誤稱已完成。
	@Test
	void recordsFailureWithoutPrivateDetails() {
		var service = new QuotationProgressService();
		assertThatThrownBy(() -> service.track("owner", () -> {
			throw new QuotationAiException("AI_AUTH_FAILED", "private-key");

		})).isInstanceOf(QuotationAiException.class);
		assertThat(service.status("owner")).contains("已停止", "AI_AUTH_FAILED").doesNotContain("private-key", "處理完成");
	}
}
