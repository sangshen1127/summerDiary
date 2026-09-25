-- ══════════════════════════════════════════════════════════════
-- V1 —— 基础表结构（user / diary / tag / diary_tag）
-- ══════════════════════════════════════════════════════════════
-- 表结构设计见开发文档 §7.1。
--
-- ⚠️ 迁移脚本规则（开发文档 §7.2）：
--   1. 已执行过的脚本**永不修改**，需要变更就加新版本号（V2__xxx.sql）
--   2. 只允许前后端负责人写迁移脚本；AI 模块需要新表新列要提需求
--   3. 所有时间列统一存 UTC（连接串已配 serverTimezone=UTC）
--   4. 需要筛选/排序的字段必须单独建列，不要塞进 JSON 里
--   5. 生产与本地执行同一套脚本，禁止手工建表
--
-- 【本文件的一次性例外说明】
--   Phase 0 的 V1 只有 user 表（用于验证迁移机制可用）。
--   模块 1-1 把 4 张表合并进 V1，语义更完整（V1 = 初始表结构）。
--   这么做的前提是：当时只有本地开发库、user 表为空、无任何真实数据。
--   ⚠️ 从 V2 开始，规则 1 严格执行，不再有任何例外。
-- ══════════════════════════════════════════════════════════════


-- ──────────────────────────────────────────────────────────────
-- user —— 用户
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `user`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '登录名，全站唯一',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，形如 $2a$10$...，60 字符',
    `nickname`      VARCHAR(50)  NULL COMMENT '显示名，可为空',
    `ai_enabled`    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否开启 AI 分析，0 关闭',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / DELETED',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户';


-- ──────────────────────────────────────────────────────────────
-- diary —— 日记
-- ──────────────────────────────────────────────────────────────
-- ⚠️ content_ciphertext 存的是 AES-256-GCM 密文，
--    格式 Base64( IV(12字节) || ciphertext || authTag(16字节) )。
--    用 TEXT 而非 BLOB：Base64 便于调试，代价是体积大 33%。
--    加密实现见模块 2-1，密钥从环境变量 DIARY_ENCRYPTION_KEY 读取。
--
-- deleted 是软删除标记：日记删除需要触发「来源记忆 + 向量」的补偿任务，
-- 立即物理删除会让补偿任务失去依据。
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `diary`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`            BIGINT       NOT NULL COMMENT '所属用户',
    `title`              VARCHAR(200) NOT NULL COMMENT '标题',
    `content_ciphertext` TEXT         NOT NULL COMMENT '正文密文，Base64(IV||ciphertext||tag)',
    `mood`               VARCHAR(20)  NULL COMMENT '用户填写的心情，如 开心/平静/焦虑',
    `weather`            VARCHAR(20)  NULL COMMENT '用户填写的天气',
    `location`           VARCHAR(100) NULL COMMENT '地点，第一版仅文本，不存坐标',
    `deleted`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除，0 正常 / 1 已删除',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC，日记时间',
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC',
    PRIMARY KEY (`id`),
    -- 列表页最频繁的查询：WHERE user_id=? AND deleted=0 ORDER BY created_at DESC
    -- deleted 放在索引里，避免「先按索引找到全部日记再回表过滤 deleted」
    KEY `idx_diary_user_deleted_created` (`user_id`, `deleted`, `created_at`),
    -- 按标签筛选日记时需要反查（配合 diary_tag）
    KEY `idx_diary_user_mood` (`user_id`, `mood`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='日记';


-- ──────────────────────────────────────────────────────────────
-- tag —— 标签
-- ──────────────────────────────────────────────────────────────
-- (user_id, name) 唯一：同名标签复用，不重复插入。
-- 注意唯一键里必须带 user_id —— 标签是用户私有的，
-- 两个用户都可以有叫「读书」的标签。
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `tag`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT      NOT NULL COMMENT '所属用户',
    `name`       VARCHAR(30) NOT NULL COMMENT '标签名，同一用户内唯一',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_user_name` (`user_id`, `name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='标签';


-- ──────────────────────────────────────────────────────────────
-- diary_tag —— 日记与标签的关联
-- ──────────────────────────────────────────────────────────────
-- 联合主键天然保证「同一篇日记不会重复关联同一个标签」。
-- 反向索引 (tag_id, diary_id)：支持「按标签筛选日记」。
--
-- ⚠️ 本表没有 user_id 列。所有涉及本表的查询都必须
--    通过 JOIN diary 并带 diary.user_id 条件来完成权限隔离 ——
--    不能仅凭 diary_id 就认为有权访问。见开发文档 §4.7。
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `diary_tag`
(
    `diary_id` BIGINT NOT NULL COMMENT '日记 ID',
    `tag_id`   BIGINT NOT NULL COMMENT '标签 ID',
    PRIMARY KEY (`diary_id`, `tag_id`),
    KEY `idx_diary_tag_tag` (`tag_id`, `diary_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='日记-标签关联';
