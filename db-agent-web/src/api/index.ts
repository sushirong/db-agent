import axios from 'axios';
import type {
  AgentQueryRequest,
  AgentQueryResponse,
  AgentStreamEvent,
  Conversation,
  Message,
  SchemaSyncEvent,
  SchemaSyncResponse,
} from '../types';

const apiClient = axios.create({
  baseURL: '/api/python',
  timeout: 180000, // 3 分钟超时，LLM 调用可能较慢
  headers: {
    'Content-Type': 'application/json',
  },
});

const javaApiClient = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * 发送 Agent 查询请求
 */
export async function queryAgent(request: AgentQueryRequest): Promise<AgentQueryResponse> {
  const response = await apiClient.post<AgentQueryResponse>('/ai/agent/query', request);
  return response.data;
}

/**
 * 发送 Agent 流式查询请求
 * SSE 数据块会被解析为结构化事件，页面可以逐段更新助手消息
 */
export async function queryAgentStream(
  request: AgentQueryRequest,
  onEvent: (event: AgentStreamEvent) => void
): Promise<void> {
  const response = await fetch('/api/python/ai/agent/query/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`流式查询请求失败: ${response.status}`);
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应读取');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const emitBufferedEvents = () => {
    let eventEndIndex = buffer.indexOf('\n\n');
    while (eventEndIndex !== -1) {
      const eventBlock = buffer.slice(0, eventEndIndex).trim();
      buffer = buffer.slice(eventEndIndex + 2);

      const dataText = eventBlock
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n');

      if (dataText) {
        onEvent(JSON.parse(dataText) as AgentStreamEvent);
      }

      eventEndIndex = buffer.indexOf('\n\n');
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    emitBufferedEvents();

    if (done) {
      break;
    }
  }

  if (buffer.trim()) {
    const dataText = buffer
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');

    if (dataText) {
      onEvent(JSON.parse(dataText) as AgentStreamEvent);
    }
  }
}

/**
 * 同步表结构到 RAG 知识库
 */
export async function syncSchema(tableName: string, schemaText: string, tableComment?: string): Promise<SchemaSyncResponse> {
  const response = await apiClient.post<SchemaSyncResponse>('/ai/schema/sync', {
    tableName,
    tableComment,
    schemaText,
  });
  return response.data;
}

/**
 * 同步所有表结构 (调用 Java 端的同步接口)
 */
export async function syncAllSchemas(databaseName: string): Promise<{ success: boolean; message: string }> {
  const response = await javaApiClient.post(`/api/schema/${databaseName}/sync`);
  return response.data;
}

/**
 * 同步表结构 (SSE 流式推送进度)
 * 默认增量同步，force=true 时全量同步
 */
export async function syncAllSchemasStream(
  databaseName: string,
  onEvent: (event: SchemaSyncEvent) => void,
  force: boolean = false
): Promise<void> {
  const response = await fetch(`http://localhost:8080/api/schema/${databaseName}/sync?force=${force}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
  });

  if (!response.ok) {
    throw new Error(`同步请求失败: ${response.status}`);
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应读取');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const emitBufferedEvents = () => {
    let eventEndIndex = buffer.indexOf('\n\n');
    while (eventEndIndex !== -1) {
      const eventBlock = buffer.slice(0, eventEndIndex).trim();
      buffer = buffer.slice(eventEndIndex + 2);

      const dataText = eventBlock
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n');

      if (dataText) {
        onEvent(JSON.parse(dataText) as SchemaSyncEvent);
      }

      eventEndIndex = buffer.indexOf('\n\n');
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    emitBufferedEvents();

    if (done) {
      break;
    }
  }

  if (buffer.trim()) {
    const dataText = buffer
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');

    if (dataText) {
      onEvent(JSON.parse(dataText) as SchemaSyncEvent);
    }
  }
}

/**
 * 获取永久保存的历史会话列表
 */
export async function listConversations(): Promise<Conversation[]> {
  const response = await javaApiClient.get<Conversation[]>('/api/conversations');
  return response.data;
}

/**
 * 获取指定历史会话的完整消息列表
 */
export async function listConversationMessages(conversationId: string): Promise<Message[]> {
  const response = await javaApiClient.get<Message[]>(`/api/conversations/${conversationId}/messages`);
  return response.data;
}

/**
 * 将消息永久保存到后端数据库
 */
export async function saveConversationMessage(conversationId: string, message: Message): Promise<Conversation> {
  const response = await javaApiClient.post<Conversation>(`/api/conversations/${conversationId}/messages`, {
    id: message.id,
    role: message.role,
    content: message.content,
    timestamp: message.timestamp,
  });
  return response.data;
}
