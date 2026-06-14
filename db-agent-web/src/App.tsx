import { useState, useCallback, useEffect, useMemo } from 'react';
import Sidebar from './components/layout/Sidebar';
import ChatArea from './components/layout/ChatArea';
import Toast from './components/ui/Toast';
import {
  listConversationMessages,
  listConversations,
  queryAgentStream,
  saveConversationMessage,
  syncAllSchemasStream,
} from './api';
import type { AgentStreamEvent, Message, Conversation, SchemaSyncEvent } from './types';
import type { SyncProgress } from './components/layout/Sidebar';

// 生成唯一 ID
const generateId = () => `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

// 查询结果会转换为 Markdown 表格，便于消息气泡直接渲染
const formatMarkdownTable = (data: Record<string, unknown>[]) => {
  if (data.length === 0) {
    return '';
  }

  const headers = Object.keys(data[0]);
  let tableContent = '**查询结果：**\n\n';
  tableContent += '| ' + headers.join(' | ') + ' |\n';
  tableContent += '| ' + headers.map(() => '---').join(' | ') + ' |\n';
  data.forEach((row) => {
    tableContent += '| ' + headers.map((header) => String(row[header] ?? '')).join(' | ') + ' |\n';
  });
  return tableContent;
};

// 流式状态、业务回答、SQL 和查询结果会组合为助手消息正文
const buildAssistantContent = (
  statusText: string,
  answerText: string,
  generatedSql: string | null,
  resultData: Record<string, unknown>[] | null
) => {
  const sections: string[] = [];

  if (answerText.trim()) {
    sections.push(answerText.trim());
  } else if (statusText) {
    sections.push(statusText);
  }

  if (generatedSql) {
    sections.push('**生成的 SQL：**\n```sql\n' + generatedSql + '\n```');
  }

  if (resultData && resultData.length > 0) {
    sections.push(formatMarkdownTable(resultData));
  }

  return sections.join('\n\n').trim();
};

interface ToastState {
  message: string;
  type: 'success' | 'error';
}

export default function App() {
  // 会话状态
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);

  // 消息状态 - 按会话 ID 存储
  const [messagesMap, setMessagesMap] = useState<Record<string, Message[]>>({});

  // 加载状态
  const [isLoading, setIsLoading] = useState(false);

  // Toast 状态
  const [toast, setToast] = useState<ToastState | null>(null);

  // 历史会话加载状态
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);

  // 同步进度状态
  const [syncProgress, setSyncProgress] = useState<SyncProgress | null>(null);

  // 历史会话使用更新时间倒序展示
  const sortedConversations = useMemo(
    () => [...conversations].sort((a, b) => b.timestamp - a.timestamp),
    [conversations]
  );

  // 当前消息列表
  const currentMessages = activeConversationId
    ? messagesMap[activeConversationId] || []
    : [];

  // 页面初始化会从 Java 服务加载永久保存的会话列表
  useEffect(() => {
    const loadHistory = async () => {
      setIsHistoryLoading(true);
      try {
        const historyConversations = await listConversations();
        setConversations(historyConversations);
      } catch (error) {
        setToast({
          message: '加载历史会话失败: ' + (error instanceof Error ? error.message : '未知错误'),
          type: 'error',
        });
      } finally {
        setIsHistoryLoading(false);
      }
    };

    loadHistory();
  }, []);

  // 消息保存到 Java 服务，左侧历史列表同步服务端摘要数据
  const persistMessage = useCallback(async (conversationId: string, message: Message) => {
    try {
      const savedConversation = await saveConversationMessage(conversationId, message);
      setConversations((prev) => {
        const exists = prev.some((conversation) => conversation.id === savedConversation.id);
        if (exists) {
          return prev.map((conversation) =>
            conversation.id === savedConversation.id ? savedConversation : conversation
          );
        }
        return [savedConversation, ...prev];
      });
    } catch (error) {
      setToast({
        message: '保存会话历史失败: ' + (error instanceof Error ? error.message : '未知错误'),
        type: 'error',
      });
    }
  }, []);

  // 新建对话
  const handleNewConversation = useCallback(() => {
    setActiveConversationId(null);
  }, []);

  // 选择会话
  const handleSelectConversation = useCallback(async (id: string) => {
    setActiveConversationId(id);

    if (messagesMap[id]) {
      return;
    }

    setIsHistoryLoading(true);
    try {
      const messages = await listConversationMessages(id);
      setMessagesMap((prev) => ({
        ...prev,
        [id]: messages,
      }));
    } catch (error) {
      setToast({
        message: '加载会话消息失败: ' + (error instanceof Error ? error.message : '未知错误'),
        type: 'error',
      });
    } finally {
      setIsHistoryLoading(false);
    }
  }, [messagesMap]);

  // 发送消息
  const handleSendMessage = useCallback(async (content: string) => {
    // 如果没有活跃会话，创建一个新会话
    let conversationId = activeConversationId;
    if (!conversationId) {
      conversationId = generateId();
      setActiveConversationId(conversationId);
    }

    // 添加用户消息
    const userMessage: Message = {
      id: generateId(),
      role: 'user',
      content,
      timestamp: Date.now(),
    };

    // 助手消息会被流式事件持续更新
    const assistantMessage: Message = {
      id: generateId(),
      role: 'assistant',
      content: '正在连接 AI 服务...',
      timestamp: Date.now(),
      isLoading: true,
    };

    setMessagesMap((prev) => ({
      ...prev,
      [conversationId!]: [...(prev[conversationId!] || []), userMessage, assistantMessage],
    }));

    setIsLoading(true);

    try {
      // 用户消息写入服务端历史记录
      await persistMessage(conversationId, userMessage);

      let statusText = '正在连接 AI 服务...';
      let answerText = '';
      let generatedSql: string | null = null;
      let resultData: Record<string, unknown>[] | null = null;
      let finalContent = statusText;

      // 当前助手消息会在每个流式事件到达时刷新
      const updateAssistantMessage = (nextContent: string, isStreaming = true) => {
        finalContent = nextContent;
        setMessagesMap((prev) => ({
          ...prev,
          [conversationId!]: (prev[conversationId!] || []).map((message) =>
            message.id === assistantMessage.id
              ? {
                  ...message,
                  content: nextContent,
                  isLoading: isStreaming,
                }
              : message
          ),
        }));
      };

      // SSE 事件会实时驱动当前助手消息内容变化
      await queryAgentStream({ query: content }, (event: AgentStreamEvent) => {
        if (event.type === 'status') {
          statusText = event.message;
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData));
          return;
        }

        if (event.type === 'sql') {
          generatedSql = event.generated_sql;
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData));
          return;
        }

        if (event.type === 'data') {
          resultData = event.data;
          statusText = '正在生成业务回答...';
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData));
          return;
        }

        if (event.type === 'answer_start') {
          statusText = '';
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData));
          return;
        }

        if (event.type === 'answer_delta') {
          answerText += event.content;
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData));
          return;
        }

        if (event.type === 'done') {
          statusText = '';
          answerText = event.answer || answerText;
          generatedSql = event.generated_sql || generatedSql;
          resultData = event.data || resultData;
          updateAssistantMessage(buildAssistantContent(statusText, answerText, generatedSql, resultData), false);
          return;
        }

        if (event.type === 'error') {
          updateAssistantMessage(`**查询失败**\n\n${event.error || '未知错误'}`, false);
        }
      });

      // 助手最终回复写入服务端历史记录
      await persistMessage(conversationId, {
        ...assistantMessage,
        content: finalContent.trim(),
        timestamp: Date.now(),
        isLoading: false,
      });
    } catch (error) {
      // 错误处理
      const errorMessage: Message = {
        id: assistantMessage.id,
        role: 'assistant',
        content: `**请求失败**\n\n${error instanceof Error ? error.message : '网络错误，请检查服务是否正常运行'}`,
        timestamp: Date.now(),
      };

      setMessagesMap((prev) => ({
        ...prev,
        [conversationId!]: [...(prev[conversationId!] || []).slice(0, -1), errorMessage],
      }));

      await persistMessage(conversationId, errorMessage);
    } finally {
      setIsLoading(false);
    }
  }, [activeConversationId, persistMessage]);

  // 同步表结构 (SSE 流式进度，默认增量，force=true 全量)
  const handleSyncSchema = useCallback(async (force: boolean = false) => {
    setSyncProgress(null);
    try {
      await syncAllSchemasStream('dataset', (event: SchemaSyncEvent) => {
        if (event.type === 'start') {
          setSyncProgress({ current: 0, total: event.total, tableName: '', status: 'syncing' });
        }

        if (event.type === 'progress') {
          setSyncProgress({
            current: event.current,
            total: event.total,
            tableName: event.tableName,
            status: event.status === 'success' ? 'success' : 'fail',
          });
        }

        if (event.type === 'complete') {
          setSyncProgress(null);
          setToast({
            message: event.message || '表结构同步成功',
            type: event.fail > 0 ? 'error' : 'success',
          });
        }
      }, force);
    } catch (error) {
      setSyncProgress(null);
      setToast({
        message: '同步失败: ' + (error instanceof Error ? error.message : '未知错误'),
        type: 'error',
      });
    }
  }, []);

  return (
    <div className="flex h-screen bg-gray-100 overflow-hidden">
      {/* 左侧边栏 */}
      <Sidebar
        conversations={sortedConversations}
        activeConversationId={activeConversationId}
        onSelectConversation={handleSelectConversation}
        onNewConversation={handleNewConversation}
        onSyncSchema={handleSyncSchema}
        isHistoryLoading={isHistoryLoading}
        syncProgress={syncProgress}
      />

      {/* 主对话区 */}
      <ChatArea
        messages={currentMessages}
        onSendMessage={handleSendMessage}
        isLoading={isLoading}
      />

      {/* Toast 提示 */}
      {toast && (
        <Toast
          message={toast.message}
          type={toast.type}
          onClose={() => setToast(null)}
        />
      )}
    </div>
  );
}
