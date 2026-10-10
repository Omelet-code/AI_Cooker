-- U1独占V10：默认人数是用户显式设置，不补写既有用户或推断历史聊天。
CREATE TABLE user_profile (
    user_id BIGINT PRIMARY KEY,
    default_dining_count INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_profile_positive_count CHECK (default_dining_count IS NULL OR default_dining_count > 0)
);
