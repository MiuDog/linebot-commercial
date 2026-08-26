package dev.miudog.linebotcommercial.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 驗證方法追蹤的完整 DEBUG 診斷與一般 INFO 快速路徑。
 */
@ExtendWith(OutputCaptureExtension.class)
class MethodTraceLoggerTest {

	//#region 欄位

	private final Logger flowLogger = (Logger) LoggerFactory.getLogger("FLOW_TRACE");
	private final Level previousLevel = flowLogger.getLevel();

	//#endregion

	//#region 方法

	// 方法：還原測試前 Log 等級並清除 MDC，避免測試狀態互相污染。
	@AfterEach
	void restoreLoggingState() {
		flowLogger.setLevel(previousLevel);
		MDC.clear();
	}

	// 方法：DEBUG 診斷模式記錄目前 Request ID、方法進入與完成耗時。
	@Test
	void logsMethodEntryAndCompletionWithTheCurrentRequestId(CapturedOutput output) throws Throwable {
		flowLogger.setLevel(Level.DEBUG);
		MDC.put("requestId", "request-123");
		ProceedingJoinPoint joinPoint = joinPointReturning("done");

		Object result = new MethodTraceLogger().trace(joinPoint);

		assertThat(result).isEqualTo("done");
		assertThat(output)
			.contains("event=method_entered")
			.contains("event=method_completed")
			.contains("requestId=request-123")
			.contains("class=ExampleService")
			.contains("method=execute")
			.contains("durationMs=");
	}

	// 方法：方法失敗時記錄錯誤類型但不洩漏例外訊息或呼叫參數。
	@Test
	void logsMethodFailureWithoutLoggingArguments(CapturedOutput output) throws Throwable {
		flowLogger.setLevel(Level.DEBUG);
		MDC.put("requestId", "request-456");
		ProceedingJoinPoint joinPoint = joinPointThrowing(new IllegalStateException("secret-value"));

		assertThatThrownBy(() -> new MethodTraceLogger().trace(joinPoint)).isInstanceOf(IllegalStateException.class);

		assertThat(output)
			.contains("event=method_failed")
			.contains("requestId=request-456")
			.contains("errorType=IllegalStateException")
			.doesNotContain("secret-value");
	}

	// 方法：DEBUG 關閉時直接執行原方法，成功路徑不解析簽章或建立追蹤資料。
	@Test
	void shouldBypassTraceMetadataWhenDebugLoggingIsDisabled() throws Throwable {
		flowLogger.setLevel(Level.INFO);
		ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
		when(joinPoint.proceed()).thenReturn("completed");

		Object result = new MethodTraceLogger().trace(joinPoint);

		assertThat(result).isEqualTo("completed");
		verify(joinPoint, never()).getSignature();
	}

	// 方法：建立固定成功結果的模擬方法呼叫。
	private static ProceedingJoinPoint joinPointReturning(Object result) throws Throwable {
		ProceedingJoinPoint joinPoint = baseJoinPoint();
		when(joinPoint.proceed()).thenReturn(result);

		return joinPoint;
	}

	// 方法：建立固定拋出錯誤的模擬方法呼叫。
	private static ProceedingJoinPoint joinPointThrowing(Throwable error) throws Throwable {
		ProceedingJoinPoint joinPoint = baseJoinPoint();
		when(joinPoint.proceed()).thenThrow(error);

		return joinPoint;
	}

	// 方法：建立可辨識類別與方法名稱的模擬方法呼叫。
	private static ProceedingJoinPoint baseJoinPoint() {
		ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
		MethodSignature signature = mock(MethodSignature.class);
		when(joinPoint.getSignature()).thenReturn(signature);
		when(signature.getDeclaringType()).thenReturn(ExampleService.class);
		when(signature.getName()).thenReturn("execute");

		return joinPoint;
	}

	//#endregion

	/**
	 * 提供測試用的固定方法宣告類別。
	 */
	private static final class ExampleService {
	}
}
