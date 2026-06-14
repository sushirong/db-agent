// 消息角色类型
export type MessageRole = 'user' | 'assistant';

// 消息接口
export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: number;
  isLoading?: boolean;
}

// 对话消息（传给 Python 的精简格式）
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

// Agent 查询请求
export interface AgentQueryRequest {
  query: string;
  databaseName?: string;
  history?: ChatMessage[];
}

// Agent 查询响应
export interface AgentQueryResponse {
  success: boolean;
  query: string;
  generated_sql: string | null;
  data: Record<string, unknown>[] | null;
  answer: string | null;
  error: string | null;
  retry_count: number;
}

// Agent 流式事件类型
export type AgentStreamEvent =
  | {
      type: 'status';
      message: string;
      error?: string;
    }
  | {
      type: 'sql';
      generated_sql: string;
    }
  | {
      type: 'data';
      data: Record<string, unknown>[];
      retry_count: number;
    }
  | {
      type: 'answer_start';
    }
  | {
      type: 'answer_delta';
      content: string;
    }
  | {
      type: 'done';
      success: boolean;
      query: string;
      generated_sql: string | null;
      data: Record<string, unknown>[] | null;
      answer: string | null;
      error: string | null;
      retry_count: number;
    }
  | {
      type: 'error';
      success: false;
      query: string;
      generated_sql: string | null;
      data: null;
      answer: null;
      error: string;
      retry_count: number;
    };

// 表结构同步响应
export interface SchemaSyncResponse {
  success: boolean;
  message: string;
}

// 表结构同步 SSE 事件类型
export type SchemaSyncEvent =
  | {
      type: 'start';
      total: number;
    }
  | {
      type: 'progress';
      current: number;
      total: number;
      tableName: string;
      status: 'success' | 'fail';
      message?: string;
    }
  | {
      type: 'complete';
      total: number;
      success: number;
      fail: number;
      message: string;
    };

// 会话接口
export interface Conversation {
  id: string;
  title: string;
  lastMessage: string;
  timestamp: number;
}
