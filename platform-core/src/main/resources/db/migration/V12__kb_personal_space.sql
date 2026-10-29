-- V12：个人知识库基础（platform-core 第一批）。
--
-- 设计说明：一人一库靠「我的知识库（{username}）」的固定命名 + 既有的 uk_kb_name
-- 全局唯一索引天然保证（username 唯一 ⇒ 命名必不重复），不新增唯一约束——
-- MySQL 没有部分唯一索引，硬加「(owner, space_type) 唯一」要么引入冗余列要么
-- 误伤平台库，得不偿失。
--
-- 兼容性：存量行与种子数据（owner='admin' 的平台库）走 DEFAULT 'PLATFORM'，零破坏；
-- 个人库相关 API 在 app-knowledge 批次接入，本迁移只铺数据结构。

ALTER TABLE kb_space
    ADD COLUMN space_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM'
        COMMENT '空间类型：PLATFORM 平台运营管理（平台管理页可见）/ PERSONAL 用户个人库（仅本人可见）';

-- 个人库按 owner 隔离检索与查询，owner 列必须能走索引
ALTER TABLE kb_space
    ADD INDEX idx_kbspace_owner (owner);

-- 网页抓取入库的原始出处：个人库「给 URL 存正文」场景下，出处要在文档详情可回溯
ALTER TABLE kb_document
    ADD COLUMN source_url VARCHAR(512) NULL COMMENT '网页抓取入库的原始出处（URL 文档专有）';
