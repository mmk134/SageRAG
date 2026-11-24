-- PostgreSQL 初始化数据（SageRAG）
-- 默认账号：admin / admin（首次登录后请修改密码）

INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES (2001523723396308993, 'admin', 'admin', 'admin', '', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- 意图树默认引用的两个知识库（与 IntentTreeFactory 中的 KB_ID 保持一致）
INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, updated_by, create_time, update_time, deleted)
VALUES
  (1997855927072321537, '员工手册与制度', 'bailian-emb-v4', 'kb_employee_handbook', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
  (1997857139737882625, '业务系统手册', 'bailian-emb-v4', 'kb_business_system', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- 聊天页示例问题
INSERT INTO t_sample_question (id, title, description, question, create_time, update_time, deleted)
VALUES
  (2001523723396309101, '请假流程', '人事制度', '请假流程是怎样的？', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
  (2001523723396309102, 'VPN 故障', 'IT 支持', '公司 VPN 连不上怎么办？', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
  (2001523723396309103, '差旅报销', '财务制度', '差旅报销需要哪些资料？', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
  (2001523723396309104, 'OA 功能', '业务系统', 'OA 系统主要提供哪些功能？', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;
