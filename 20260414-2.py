在设计AI Agent的历史会话功能时，核心需求是：1. 展示会话列表；2. 点击某条会话展示详情（当时的对话流）。这本质上是一个典型的父子级（一对多）关系模型。

OpenGauss 完全兼容 PostgreSQL 协议，因此我们可以利用其强大的 JSONB 数据类型来存储 AI Agent 特有的结构化数据（如 Function Call 的参数、Token 消耗等）。

以下是完整的表结构设计和 Python 代码实现：

一、 数据库表结构设计
我们需要两张表：conversations（会话主表）和 messages（消息明细表）。

	-- 1. 创建会话主表
	CREATE TABLE conversations (
	    id BIGSERIAL PRIMARY KEY,
	    user_id VARCHAR(64) NOT NULL,          -- 用户标识
	    title VARCHAR(255) NOT NULL DEFAULT '新会话', -- 会话标题（可由LLM自动生成）
	    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
	    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP -- 用于会话列表按最新时间排序
	);
	-- 为用户ID和更新时间创建联合索引，加速“查询某用户最近会话列表”
	CREATE INDEX idx_conv_user_updated ON conversations(user_id, updated_at DESC);
	-- 2. 创建消息明细表
	CREATE TABLE messages (
	    id BIGSERIAL PRIMARY KEY,
	    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, -- 关联会话，级联删除
	    role VARCHAR(20) NOT NULL,             -- 角色：system, user, assistant, tool
	    content TEXT,                          -- 消息文本内容
	    meta_data JSONB DEFAULT '{}',          -- 扩展字段：存token消耗、function call参数、耗时等
	    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
	);
	-- 为会话ID和创建时间创建联合索引，加速“查询某会话的对话详情”
	CREATE INDEX idx_msg_conv_created ON messages(conversation_id, created_at ASC);
	
设计亮点：

分离存储：会话列表查询不需要扫描海量消息表，效率高。
级联删除：删除会话时，自动删除该会话下的所有消息。
JSONB字段：AI Agent 常常包含工具调用和 Token 统计，用 meta_data 统一存储，避免频繁加列。例如：{"tool_call_id": "call_123", "token_usage": {"prompt": 50, "completion": 100}}。

二、 Python 代码实现
我们将使用 psycopg2 （OpenGauss/PG 官方推荐驱动）来实现数据访问层。

首先安装依赖：
pip install psycopg2-binary

1. 数据库配置与基础类
	import psycopg2
	from psycopg2.extras import Json
	from datetime import datetime
	from typing import List, Dict, Optional
	# OpenGauss 数据库连接配置
	DB_CONFIG = {
	    "host": "127.0.0.1",
	    "port": "5432",
	    "database": "agent_db",
	    "user": "your_username",
	    "password": "your_password"
	}
	class ChatHistoryManager:
	    def __init__(self, db_config):
	        self.db_config = db_config
	        self.conn = None
	    def connect(self):
	        """建立数据库连接"""
	        self.conn = psycopg2.connect(**self.db_config)
	        self.conn.autocommit = False
	    def close(self):
	        """关闭数据库连接"""
	        if self.conn:
	            self.conn.close()
	    def _ensure_connection(self):
	        """确保连接活跃（简易重连机制）"""
	        if not self.conn or self.conn.closed:
	            self.connect()
	            
	            
	            
2. 核心业务逻辑实现
	    def create_conversation(self, user_id: str, title: str = "新会话") -> int:
	        """创建新会话，返回会话ID"""
	        sql = """
	            INSERT INTO conversations (user_id, title) 
	            VALUES (%s, %s) 
	            RETURNING id;
	        """
	        try:
	            with self.conn.cursor() as cur:
	                cur.execute(sql, (user_id, title))
	                conv_id = cur.fetchone()[0]
	                self.conn.commit()
	                return conv_id
	        except Exception as e:
	            self.conn.rollback()
	            raise RuntimeError(f"创建会话失败: {e}")
	    def add_message(self, conversation_id: int, role: str, content: str, meta_data: Optional[Dict] = None):
	        """向指定会话添加消息，并更新会话的 updated_at 时间"""
	        insert_msg_sql = """
	            INSERT INTO messages (conversation_id, role, content, meta_data) 
	            VALUES (%s, %s, %s, %s);
	        """
	        update_conv_sql = """
	            UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = %s;
	        """
	        try:
	            with self.conn.cursor() as cur:
	                # 1. 插入消息
	                cur.execute(insert_msg_sql, (conversation_id, role, content, Json(meta_data or {})))
	                # 2. 更新会话活跃时间（保证会话列表排序正确）
	                cur.execute(update_conv_sql, (conversation_id,))
	                self.conn.commit()
	        except Exception as e:
	            self.conn.rollback()
	            raise RuntimeError(f"添加消息失败: {e}")
	    def list_conversations(self, user_id: str, limit: int = 20, offset: int = 0) -> List[Dict]:
	        """获取用户的会话列表（按最新活跃时间倒序）"""
	        sql = """
	            SELECT id, title, updated_at 
	            FROM conversations 
	            WHERE user_id = %s 
	            ORDER BY updated_at DESC 
	            LIMIT %s OFFSET %s;
	        """
	        try:
	            with self.conn.cursor() as cur:
	                cur.execute(sql, (user_id, limit, offset))
	                rows = cur.fetchall()
	                return [
	                    {"id": row[0], "title": row[1], "updated_at": row[2].strftime("%Y-%m-%d %H:%M:%S")}
	                    for row in rows
	                ]
	        except Exception as e:
	            raise RuntimeError(f"获取会话列表失败: {e}")
	    def get_conversation_messages(self, conversation_id: int) -> List[Dict]:
	        """获取某个会话的完整对话记录（按时间正序，适合前端聊天气泡展示）"""
	        sql = """
	            SELECT id, role, content, meta_data, created_at 
	            FROM messages 
	            WHERE conversation_id = %s 
	            ORDER BY created_at ASC;
	        """
	        try:
	            with self.conn.cursor() as cur:
	                cur.execute(sql, (conversation_id,))
	                rows = cur.fetchall()
	                messages = []
	                for row in rows:
	                    msg = {
	                        "id": row[0],
	                        "role": row[1],
	                        "content": row[2],
	                        "meta_data": row[3],  # psycopg2 会自动将 JSONB 转为 Python dict
	                        "created_at": row[4].strftime("%Y-%m-%d %H:%M:%S")
	                    }
	                    messages.append(msg)
	                return messages
	        except Exception as e:
	            raise RuntimeError(f"获取对话详情失败: {e}")
	            
3. 模拟业务调用示例
	if __name__ == "__main__":
	    manager = ChatHistoryManager(DB_CONFIG)
	    manager.connect()
	    try:
	        user_id = "user_001"
	        # 1. 用户发起第一轮对话，创建会话
	        conv_id = manager.create_conversation(user_id, title="帮我写一段Python代码")
	        print(f"创建会话成功，ID: {conv_id}")
	        # 2. 记录用户提问
	        manager.add_message(conv_id, role="user", content="怎么用Python读CSV文件？")
	        # 3. 记录AI回答及附加信息
	        manager.add_message(
	            conversation_id=conv_id, 
	            role="assistant", 
	            content="你可以使用 pandas 的 read_csv 方法...",
	            meta_data={"token_usage": {"prompt_tokens": 15, "completion_tokens": 42}}
	        )
	        # 4. 记录工具调用 (AI Agent特有场景)
	        manager.add_message(
	            conversation_id=conv_id,
	            role="tool",
	            content="{'status': 200, 'data': '...'}",
	            meta_data={"tool_name": "csv_reader", "tool_call_id": "call_abc_123"}
	        )
	        # 5. 展示用户的会话列表（左侧边栏）
	        print("\n--- 会话列表 ---")
	        conv_list = manager.list_conversations(user_id)
	        for conv in conv_list:
	            print(f"ID: {conv['id']} | 标题: {conv['title']} | 更新时间: {conv['updated_at']}")
	        # 6. 用户点击该会话，展示对话详情（右侧聊天区）
	        print("\n--- 对话详情 ---")
	        messages = manager.get_conversation_messages(conv_id)
	        for msg in messages:
	            print(f"[{msg['role']}]: {msg['content']}")
	            if msg['role'] == 'tool':
	                print(f"  -> 调用工具: {msg['meta_data'].get('tool_name')}")
	    finally:
	        manager.close()
	        
	        
三、 开发建议与优化点
异步驱动支持：如果你使用 FastAPI 等异步框架，强烈建议将 psycopg2 替换为 asyncpg 或 psycopg (v3支持异步)，避免数据库 IO 阻塞事件循环。
历史对话截断：在将历史记录喂给 LLM 时，Token 限制是核心问题。取出 messages 后，在业务层需要实现滑动窗口或基于 Token 数的截断策略，只将最近的 N 轮对话塞入 Prompt。
标题自动生成：可以在创建会话后，使用 LLM 根据用户的第一句话自动总结一个 title，然后 Update 到 conversations 表，提升用户体验。
软删除机制：生产环境中，用户的删除操作通常建议使用 is_deleted 字段做软删除，而不是物理删除（ON DELETE CASCADE），以便数据审计和恢复。