package dev.miudog.linebotcommercial.desktop.control;

/**
 * 定義背景 service 取得與釋放單一執行個體資格的邊界。
 */
public interface ServiceInstanceResource extends AutoCloseable {

	// 方法：嘗試取得目前產品的背景 service 執行資格。
	boolean acquire();

	// 方法：釋放背景 service 執行資格。
	@Override
	void close();
}

