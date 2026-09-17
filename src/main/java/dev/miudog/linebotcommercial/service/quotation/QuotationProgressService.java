package dev.miudog.linebotcommercial.service.quotation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 單機短期進度；只存步驟文字，以使用者隔離，查詢不呼叫模型或建立草稿。 */
@Service
public class QuotationProgressService {
	private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
	private final ThreadLocal<State> current = new ThreadLocal<>();

	// 方法：清除十五分鐘前的紀錄，不保留報價內容或永久歷史。
	private void expire() {
		long cutoff = System.currentTimeMillis() - 900000;
		states.entrySet().removeIf(entry -> entry.getValue().updated < cutoff);
	}

	// 方法：包住一次報價事件；只追蹤真實執行階段，不新增主動推播。
	public <T> T track(String owner, Supplier<T> operation) {
		return track(owner, operation, "本次資料處理完成，請查看最新回覆；若已確認產檔，請等待文件交付。");
	}

	// 方法：背景產檔可提供自己的完成訊息，避免把完成交付誤報為等待產檔。
	public <T> T track(String owner, Supplier<T> operation, String completed) {
		expire();
		State state = new State();
		if (owner != null && (states.size() < 1000 || states.containsKey(owner))) states.put(owner, state);

		current.set(state);
		update("已收到資料，正在處理報價。");
		try {
			T result = operation.get();
			update(completed);
			return result;
		}
		catch (RuntimeException exception) {
			update("本次處理已停止：" + QuotationLineFailureMessageResolver.resolve(exception, QuotationLineFailureMessageResolver.Operation.TEXT).code() + "。請查看錯誤回覆。");
			throw exception;
		}
		finally {
			current.remove();
		}
	}

	// 方法：更新目前事件步驟，背景執行緒未綁定事件時不污染其他使用者。
	public void update(String stage) {
		State state = current.get();
		if (state == null) return;

		state.stage = stage;
		state.updated = System.currentTimeMillis();
	}

	// 方法：只讀取該使用者自己的進度；重啟或逾期後不猜測狀態。
	public String status(String owner) {
		expire();
		State state = owner == null ? null : states.get(owner);
		return state == null ? "目前沒有近期處理紀錄，或服務已重啟。請查看最新報價卡片。" : "目前進度：" + state.stage;
	}

	private static final class State {
		private volatile String stage = "準備處理";
		private volatile long updated = System.currentTimeMillis();
	}
}
