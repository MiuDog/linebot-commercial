package dev.myudog.assetsmanagerlinebot.observability;

/** AI 供應商單次嘗試的最終結果。 */
public enum AiAttemptStatus {
	SUCCESS,
	HTTP_ERROR,
	NETWORK_ERROR,
	TIMEOUT,
	NOT_CONFIGURED
}
