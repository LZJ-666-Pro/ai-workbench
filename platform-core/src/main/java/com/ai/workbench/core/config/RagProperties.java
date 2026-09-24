package com.ai.workbench.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 向量库连接配置（Phase 2 RAG 用），默认关闭。
 */
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

    private boolean enabled = false;
    private String host = "localhost";
    /** 宿主机端口 15432：本机 PostgreSQL 18 服务占用 5432，容器映射改为 15432:5432 */
    private int port = 15432;
    private String database = "ai_workbench_vec";
    private String user = "postgres";
    private String password = "ai123456";
    private String table = "vectors";
    /** 需与 embedding 模型输出维度一致：text-embedding-v3 是 1024 */
    private int dimension = 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }
}
