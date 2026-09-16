-- 模型成功步驟的私有快取；key 包含 owner、輸入與模型設定雜湊。
CREATE TABLE quotation_model_checkpoint (
    cache_key TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    output_budget INTEGER NOT NULL,
    total_tokens BIGINT NOT NULL,
    execution_key TEXT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_quotation_model_checkpoint_expiry ON quotation_model_checkpoint(expires_at);
