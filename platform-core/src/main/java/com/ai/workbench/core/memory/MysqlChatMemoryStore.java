package com.ai.workbench.core.memory;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话记忆持久化：每个 memoryId 一行，消息列表序列化为 JSON 存储。
 * 应用重启后会话不丢；memoryId 由各应用自行加前缀隔离（如 bank:xxx）。
 */
@Component
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbc;

    public MysqlChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT content FROM chat_memory WHERE memory_id = ?", String.class, memoryId);
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (EmptyResultDataAccessException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        jdbc.update("""
                INSERT INTO chat_memory (memory_id, content) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE content = VALUES(content)
                """, memoryId, json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM chat_memory WHERE memory_id = ?", memoryId);
    }
}
