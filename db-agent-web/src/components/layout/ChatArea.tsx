import { useState, useRef, useEffect } from 'react';
import { Send, Loader2, Database, Sparkles } from 'lucide-react';
import MessageBubble from '../chat/MessageBubble';
import type { Message } from '../../types';

interface ChatAreaProps {
  messages: Message[];
  onSendMessage: (content: string) => Promise<void>;
  isLoading: boolean;
}

export default function ChatArea({ messages, onSendMessage, isLoading }: ChatAreaProps) {
  const [input, setInput] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // 自动调整 textarea 高度
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    }
  }, [input]);

  const handleSend = async () => {
    const content = input.trim();
    if (!content || isLoading) return;

    setInput('');
    await onSendMessage(content);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleExampleClick = (text: string) => {
    setInput(text);
    textareaRef.current?.focus();
  };

  return (
    <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
      {/* 消息区域 - 关键：用 flex-1 + min-h-0 保证可滚动 */}
      <div className="flex-1 overflow-y-auto min-h-0">
        {messages.length === 0 ? (
          // 空状态
          <div className="h-full flex flex-col items-center justify-center px-6">
            <div className="w-16 h-16 rounded-2xl bg-blue-50 flex items-center justify-center mb-5">
              <Sparkles className="w-8 h-8 text-blue-500" />
            </div>
            <h2 className="text-xl font-semibold text-gray-800 mb-2">数据库智能查询</h2>
            <p className="text-sm text-gray-400 mb-8 text-center max-w-sm">
              输入你的问题，AI 将自动生成 SQL 并查询数据库返回结果
            </p>
            <div className="grid grid-cols-2 gap-3 max-w-md w-full">
              {[
                { text: '查询所有启用的用户', icon: '👤' },
                { text: '最近7天订单总金额', icon: '📊' },
                { text: '销量最好的前5个商品', icon: '🏆' },
                { text: '每个用户的订单数量', icon: '📦' },
              ].map(({ text, icon }) => (
                <button
                  key={text}
                  onClick={() => handleExampleClick(text)}
                  className="text-left px-4 py-3 rounded-xl bg-white border border-gray-200/80 text-sm text-gray-600 hover:border-blue-300 hover:bg-blue-50/50 transition-all shadow-sm"
                >
                  <span className="text-base mr-2">{icon}</span>
                  {text}
                </button>
              ))}
            </div>
          </div>
        ) : (
          // 消息列表
          <div className="max-w-3xl mx-auto px-4 py-6 space-y-5">
            {messages.map((message) => (
              <MessageBubble key={message.id} message={message} />
            ))}
            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      {/* 输入区域 - 固定在底部 */}
      <div className="flex-shrink-0 border-t border-gray-200/80 bg-white/80 backdrop-blur-sm">
        <div className="max-w-3xl mx-auto px-4 py-3">
          <div className="flex items-end gap-2">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入你的问题... (Shift+Enter 换行)"
              rows={1}
              className="flex-1 resize-none rounded-xl border border-gray-200 bg-gray-50/50 px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/30 focus:border-blue-400 transition-all placeholder:text-gray-400"
              disabled={isLoading}
            />
            <button
              onClick={handleSend}
              disabled={!input.trim() || isLoading}
              className="w-10 h-10 rounded-xl bg-blue-600 text-white flex items-center justify-center hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed transition-all flex-shrink-0 shadow-sm"
            >
              {isLoading ? (
                <Loader2 className="w-4.5 h-4.5 animate-spin" />
              ) : (
                <Send className="w-4.5 h-4.5" />
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
