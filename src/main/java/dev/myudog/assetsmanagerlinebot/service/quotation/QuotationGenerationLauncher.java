package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.springframework.stereotype.Service;

/**
 * 在正式確認交易建立持久化工作後，提供低延遲喚醒入口。
 */
@Service
public class QuotationGenerationLauncher {

	private final QuotationGenerationJobWorker worker;

	// 方法：建立只負責喚醒持久化工作者的啟動器。
	public QuotationGenerationLauncher(QuotationGenerationJobWorker worker) {
		this.worker = worker;
	}

	// 方法：確認命令只作為呼叫時機，不再把完整個資與報表狀態留在記憶體佇列。
	public void launch(QuotationConfirmedGenerationCommand command) {
		if (command == null) throw new IllegalArgumentException("背景報價產生命令不可留空");

		worker.wake();
	}
}
