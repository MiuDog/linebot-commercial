package dev.miudog.linebotcommercial.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class MethodTraceLoggerTest {

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void logsMethodEntryAndCompletionWithTheCurrentRequestId(CapturedOutput output) throws Throwable {
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

	@Test
	void logsMethodFailureWithoutLoggingArguments(CapturedOutput output) throws Throwable {
		MDC.put("requestId", "request-456");
		ProceedingJoinPoint joinPoint = joinPointThrowing(new IllegalStateException("secret-value"));

		assertThatThrownBy(() -> new MethodTraceLogger().trace(joinPoint)).isInstanceOf(IllegalStateException.class);

		assertThat(output)
			.contains("event=method_failed")
			.contains("requestId=request-456")
			.contains("errorType=IllegalStateException")
			.doesNotContain("secret-value");
	}

	private static ProceedingJoinPoint joinPointReturning(Object result) throws Throwable {
		ProceedingJoinPoint joinPoint = baseJoinPoint();
		when(joinPoint.proceed()).thenReturn(result);
		return joinPoint;
	}

	private static ProceedingJoinPoint joinPointThrowing(Throwable error) throws Throwable {
		ProceedingJoinPoint joinPoint = baseJoinPoint();
		when(joinPoint.proceed()).thenThrow(error);
		return joinPoint;
	}

	private static ProceedingJoinPoint baseJoinPoint() {
		ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
		MethodSignature signature = mock(MethodSignature.class);
		when(joinPoint.getSignature()).thenReturn(signature);
		when(signature.getDeclaringType()).thenReturn(ExampleService.class);
		when(signature.getName()).thenReturn("execute");
		return joinPoint;
	}

	private static final class ExampleService {}
}
