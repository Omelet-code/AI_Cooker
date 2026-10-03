CREATE TABLE conversation_recommendation (
    turn_id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    constraints JSONB NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_recommendation_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_recommendation_constraints_object CHECK (jsonb_typeof(constraints) = 'object'),
    CONSTRAINT ck_recommendation_result_object CHECK (jsonb_typeof(result) = 'object'),
    CONSTRAINT fk_recommendation_turn_owner FOREIGN KEY (turn_id, conversation_id, user_id)
        REFERENCES conversation_turn (id, conversation_id, user_id) ON DELETE CASCADE
);

CREATE INDEX ix_recommendation_owner_created
    ON conversation_recommendation (user_id, conversation_id, created_at DESC, turn_id DESC);
