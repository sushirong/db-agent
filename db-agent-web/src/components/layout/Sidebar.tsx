import { useState, useRef, useEffect } from 'react';
import { Database, RefreshCw, MessageSquarePlus, Loader2, CheckCircle2, XCircle } from 'lucide-react';
import type { Conversation } from '../../types';

export interface SyncProgress {
  current: number;
  total: number;
  tableName: string;
  status: 'syncing' | 'success' | 'fail';
}

interface SidebarProps {
  conversations: Conversation[];
  activeConversationId: string | null;
  onSelectConversation: (id: string) => void | Promise<void>;
  onNewConversation: () => void;
  onSyncSchema: (force?: boolean) => Promise<void>;
  isHistoryLoading: boolean;
  syncProgress: SyncProgress | null;
}

export default function Sidebar({
  conversations,
  activeConversationId,
  onSelectConversation,
  onNewConversation,
  onSyncSchema,
  isHistoryLoading,
  syncProgress,
}: SidebarProps) {
  const [isSyncing, setIsSyncing] = useState(false);
  const [showContextMenu, setShowContextMenu] = useState(false);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const syncButtonRef = useRef<HTMLButtonElement>(null);

  const handleSync = async (force: boolean = false) => {
    setShowContextMenu(false);
    setIsSyncing(true);
    try {
      await onSyncSchema(force);
    } finally {
      setIsSyncing(false);
    }
  };

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    if (!isSyncing) {
      setShowContextMenu(true);
    }
  };

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (contextMenuRef.current && !contextMenuRef.current.contains(e.target as Node)) {
        setShowContextMenu(false);
      }
    };
    if (showContextMenu) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showContextMenu]);

  return (
    <div className="w-64 bg-gray-900 text-white flex flex-col flex-shrink-0">
      {/* 顶部 Logo */}
      <div className="p-5 border-b border-gray-700/50">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-blue-600 flex items-center justify-center">
            <Database className="w-4.5 h-4.5 text-white" />
          </div>
          <div>
            <h1 className="text-base font-semibold tracking-tight">DB Query Agent</h1>
            <p className="text-[11px] text-gray-400 leading-tight">数据库智能查询助手</p>
          </div>
        </div>
      </div>

      {/* 新建对话按钮 */}
      <div className="p-3">
        <button
          onClick={onNewConversation}
          className="w-full flex items-center gap-2 px-3 py-2.5 rounded-lg border border-gray-600/50 hover:bg-gray-800 active:bg-gray-700 transition-colors text-sm"
        >
          <MessageSquarePlus className="w-4 h-4" />
          新建对话
        </button>
      </div>

      {/* 历史会话列表 */}
      <div className="flex-1 overflow-y-auto px-3 pb-3 min-h-0">
        <p className="text-[11px] text-gray-500 mb-2 px-1 uppercase tracking-wider">历史会话</p>
        <div className="space-y-1">
          {isHistoryLoading && conversations.length === 0 ? (
            <div className="flex items-center gap-2 px-3 py-2 text-xs text-gray-400">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              加载历史会话...
            </div>
          ) : conversations.length === 0 ? (
            <div className="px-3 py-2 text-xs text-gray-500">暂无历史会话</div>
          ) : conversations.map((conv) => (
            <button
              key={conv.id}
              onClick={() => onSelectConversation(conv.id)}
              className={`w-full text-left px-3 py-2.5 rounded-lg transition-colors ${
                activeConversationId === conv.id
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-gray-300 hover:bg-gray-800/60'
              }`}
            >
              <span className="block text-sm truncate">{conv.title}</span>
              <span className={`block text-[11px] truncate mt-0.5 ${
                activeConversationId === conv.id ? 'text-blue-100' : 'text-gray-500'
              }`}>
                {conv.lastMessage}
              </span>
            </button>
          ))}
        </div>
      </div>

      {/* 底部同步按钮 */}
      <div className="p-3 border-t border-gray-700/50 relative">
        <button
          ref={syncButtonRef}
          onClick={() => handleSync(false)}
          onContextMenu={handleContextMenu}
          disabled={isSyncing}
          title="左键增量同步，右键全量同步"
          className="w-full flex items-center justify-center gap-2 px-3 py-2.5 rounded-lg bg-blue-600 hover:bg-blue-700 active:bg-blue-800 disabled:bg-blue-800 disabled:cursor-not-allowed transition-colors text-sm font-medium"
        >
          {isSyncing ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              同步中...
            </>
          ) : (
            <>
              <RefreshCw className="w-4 h-4" />
              同步表结构
            </>
          )}
        </button>

        {/* 右键上下文菜单 */}
        {showContextMenu && (
          <div
            ref={contextMenuRef}
            className="absolute bottom-full left-3 right-3 mb-1 bg-gray-800 border border-gray-700 rounded-lg shadow-xl overflow-hidden"
          >
            <button
              onClick={() => handleSync(true)}
              className="w-full flex items-center gap-2 px-3 py-2.5 text-sm text-gray-200 hover:bg-gray-700 transition-colors text-left"
            >
              <RefreshCw className="w-4 h-4" />
              全量同步
            </button>
          </div>
        )}

        {/* 同步进度条 */}
        {isSyncing && syncProgress && (
          <div className="mt-3 space-y-1.5">
            <div className="flex items-center justify-between text-[11px] text-gray-400">
              <span className="truncate max-w-[160px]">
                {syncProgress.status === 'syncing'
                  ? `正在同步 ${syncProgress.tableName}`
                  : syncProgress.status === 'success'
                    ? <span className="flex items-center gap-1"><CheckCircle2 className="w-3 h-3 text-green-400" /> {syncProgress.tableName}</span>
                    : <span className="flex items-center gap-1"><XCircle className="w-3 h-3 text-red-400" /> {syncProgress.tableName}</span>
                }
              </span>
              <span>{syncProgress.current}/{syncProgress.total}</span>
            </div>
            <div className="w-full h-1.5 bg-gray-700 rounded-full overflow-hidden">
              <div
                className="h-full bg-blue-500 rounded-full transition-all duration-300"
                style={{ width: `${(syncProgress.current / syncProgress.total) * 100}%` }}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
