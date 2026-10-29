-- V3：知识库全文索引（RAG 的召回层）
--
-- 为什么是 FULLTEXT + ngram 而不是一上来就上向量库：
-- 语料是几百条内部条目，ngram 对中文的召回已经够用，且零额外组件（不用 embedding 模型、不用向量库）；
-- 向量检索真正划算的时机是"语料上千 + 需要同义/跨语言召回"，届时只换 KbService.retrieve 的实现，
-- 上层（AgentService 的拼上下文、审计、降级）完全不用动。
--
-- ngram 的作用：MySQL 默认分词器按空格切词，中文整句会被当成一个词，MATCH 基本查不到东西。
-- ngram 按 N 元字符切（默认 n=2），"打印机连不上" 才能和 "打印机离线处理" 命中。
ALTER TABLE kb_item
    ADD FULLTEXT INDEX ft_kb_item (title, content, tags) WITH PARSER ngram;
