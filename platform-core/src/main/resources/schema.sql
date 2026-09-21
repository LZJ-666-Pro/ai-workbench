-- 会话记忆持久化（platform-core MysqlChatMemoryStore 使用）
CREATE TABLE IF NOT EXISTS chat_memory (
    memory_id  VARCHAR(190) PRIMARY KEY,
    content    MEDIUMTEXT   NOT NULL,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
