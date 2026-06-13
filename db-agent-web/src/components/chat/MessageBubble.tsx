import { User, Bot, Loader2 } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import type { Message } from '../../types';

interface MessageBubbleProps {
  message: Message;
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex gap-3 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
      {/* 头像 */}
      <div
        className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 shadow-sm ${
          isUser
            ? 'bg-blue-600'
            : 'bg-emerald-600'
        }`}
      >
        {isUser ? (
          <User className="w-4 h-4 text-white" />
        ) : (
          <Bot className="w-4 h-4 text-white" />
        )}
      </div>

      {/* 消息内容 */}
      <div
        className={`max-w-[75%] min-w-0 rounded-2xl px-4 py-3 ${
          isUser
            ? 'bg-blue-600 text-white rounded-tr-md'
            : 'bg-white text-gray-800 shadow-sm border border-gray-100 rounded-tl-md'
        }`}
      >
        {isUser ? (
          <p className="text-sm whitespace-pre-wrap leading-relaxed">{message.content}</p>
        ) : (
          <div className="markdown-body text-sm overflow-hidden space-y-2">
            {message.content ? (
              <ReactMarkdown>{message.content}</ReactMarkdown>
            ) : null}
            {message.isLoading ? (
              <div className="flex items-center gap-2 text-gray-400">
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                <span className="text-xs">生成中...</span>
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}
