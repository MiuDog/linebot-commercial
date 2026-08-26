package dev.miudog.linebotcommercial.desktop.control;

/**
 * 定義 service 控制通道可回傳且不含內部細節的固定結果。
 */
public enum ServiceControlResponse {
	RUNNING,
	STOPPED,
	RESTARTED,
	SHUTTING_DOWN,
	REJECTED,
	FAILED,
	UNAVAILABLE
}
