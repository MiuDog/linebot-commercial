package dev.myudog.assetsmanagerlinebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 【職責】應用程式進入點。
 *
 * <p>本服務把 LINE 群組當成資產的收件與取件窗口：
 * 群組上傳的圖片會落地到本機磁碟，SQLite 只保存指向該檔案的路徑與標籤，
 * 需要時再由群組指令查出來、透過對外端點貼回群組。
 *
 * <p><b>啟動事件呼叫鏈：</b>
 * {@code main → SpringApplication.run → StorageConfig.dataSource → schema.sql
 * → ApplicationReadyEvent → OperationalStatusLogger}。
 * Spring 容器會先建立資產根目錄與 SQLite 連線，再初始化資料表；
 * 所有 Bean 就緒後才輸出不含金鑰的設定狀態。
 *
 * <p>LINE、HTTP 與報價的完整事件鏈請見
 * {@code docs/06-event-call-chains.md}。
 *
 * <p>完整的類別導覽請見 {@code docs/reference/index.md}。
 */
@SpringBootApplication
@EnableScheduling
public class AssetsManagerLinebotApplication {

	/**
	 * 啟動 Spring 容器。
	 *
	 * @param args 命令列參數，直接交給 Spring Boot 處理
	 */
	// 方法：執行 main 方法的處理流程。
	public static void main(String[] args) {
		SpringApplication.run(AssetsManagerLinebotApplication.class, args);
	}
}
