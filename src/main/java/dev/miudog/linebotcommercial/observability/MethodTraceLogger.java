package dev.miudog.linebotcommercial.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 【跨事件觀測鏈】追蹤所有由 Spring 管理的公開專案方法，但不記錄參數或回傳值。
 *
 * <p><b>攔截鏈：</b>
 * {@code Controller／Service／Repository 公開方法
 * → trace → method_entered → joinPoint.proceed
 * → method_completed／method_failed}。
 *
 * <p>HTTP 事件沿用 {@link RequestCorrelationFilter} 建立的 Request ID；
 * 啟動或其他背景事件若沒有 Request ID，本 Aspect 會暫時建立
 * {@code background-*} 識別碼，結束後再從 MDC 移除。
 *
 * <p>刻意不記錄方法參數與回傳值，避免 LINE Token、使用者內容、
 * 圖片位元組或 AI 資料進入日誌。可透過
 * {@code METHOD_TRACING_ENABLED=false} 關閉。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "app.observability.method-tracing-enabled", havingValue = "true", matchIfMissing = true)
public class MethodTraceLogger {

	private static final Logger log = LoggerFactory.getLogger("FLOW_TRACE");

	// 方法：執行 trace 方法的處理流程。
	@Around("""
            execution(public * dev.miudog.linebotcommercial..*(..))
            && !within(dev.miudog.linebotcommercial.observability..*)
            """)
	public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		String className = signature.getDeclaringType().getSimpleName();
		String methodName = signature.getName();
		boolean ownsRequestId = MDC.get("requestId") == null;
		if (ownsRequestId) {
			// 外部呼叫：使用 UUID 與 SLF4J MDC 建立背景流程的追蹤識別碼。
			MDC.put("requestId", "background-" + UUID.randomUUID());
		}

		String requestId = MDC.get("requestId");
		long startedAt = System.nanoTime();
		// 日誌：記錄方法開始執行。
		log.info("event=method_entered requestId={} class={} method={}", requestId, className, methodName);
		try {
			Object result = joinPoint.proceed();
			// 日誌：記錄方法完成與執行耗時。
			log.info(
				"event=method_completed requestId={} class={} method={} durationMs={}",
				requestId,
				className,
				methodName,
				elapsedMilliseconds(startedAt)
			);
			return result;
		}
		catch (Throwable error) {
			// 日誌：記錄方法失敗、耗時與例外類型。
			log.error(
				"event=method_failed requestId={} class={} method={} durationMs={} errorType={}",
				requestId,
				className,
				methodName,
				elapsedMilliseconds(startedAt),
				error.getClass().getSimpleName()
			);
			throw error;
		}
		finally {
			if (ownsRequestId) {
				MDC.remove("requestId");
			}
		}
	}

	// 方法：執行 elapsedMilliseconds 方法的處理流程。
	private static long elapsedMilliseconds(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
