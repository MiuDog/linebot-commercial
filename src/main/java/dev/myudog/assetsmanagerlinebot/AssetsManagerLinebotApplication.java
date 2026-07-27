package dev.myudog.assetsmanagerlinebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 【職責】應用程式進入點。
 *
 * <p>本服務把 LINE 群組當成資產的收件與取件窗口：
 * 群組上傳的圖片會落地到本機磁碟，SQLite 只保存指向該檔案的路徑與標籤，
 * 需要時再由群組指令查出來、透過對外端點貼回群組。
 *
 * <p>完整的類別導覽請見 {@code docs/reference/index.md}。
 */
@SpringBootApplication
public class AssetsManagerLinebotApplication {

	/**
	 * 啟動 Spring 容器。
	 *
	 * @param args 命令列參數，直接交給 Spring Boot 處理
	 */
	public static void main(String[] args) {
		SpringApplication.run(AssetsManagerLinebotApplication.class, args);
	}

}
