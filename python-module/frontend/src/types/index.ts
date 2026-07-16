// ============================================================
// 消息相关类型定义
// ============================================================

/** 消息角色 */
export type MessageRole = 'user' | 'assistant';

/** 单条消息 */
export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  created_at: string;
  /** 结构化额外数据（潜客卡片等） */
  extra?: Record<string, unknown>;
}

/** 会话 */
export interface Conversation {
  id: string;
  title: string;
  messages: Message[];
  created_at: string;
  updated_at: string;
}

/** 会话列表项 */
export interface ConversationListItem {
  id: string;
  title: string;
  message_count: number;
  created_at: string;
  updated_at: string;
}

// ============================================================
// 潜客推荐数据类型
// ============================================================

/** 客户来源 */
export interface CustomerSource {
  source_id: string;
  source_name: string;
  customer_count: number;
}

/** 客户详情 */
export interface CustomerDetail {
  name: string;
  credit_code: string;
  score: number;
}

/** 潜客汇总数据 */
export interface PotentialCustomerSummary {
  action: 'summary';
  sources: CustomerSource[];
  message?: string;
}

/** 潜客详情数据 */
export interface PotentialCustomerDetail {
  action: 'detail';
  source_id: string;
  customers: CustomerDetail[];
}

// ============================================================
// 风险预查数据类型
// ============================================================

/** 名称歧义选项 */
export interface RiskAmbiguousOption {
  credit_code: string;
  company_name: string;
}

/** 风险预查结果 */
export interface RiskCheckResult {
  action: 'result' | 'ambiguous' | 'not_found';
  credit_code?: string;
  company_name?: string;
  has_risk?: boolean;
  risk_level?: string;
  risk_summary?: string;
  h5_url?: string;
  message?: string;
  keyword?: string;
  options?: RiskAmbiguousOption[];
}

// ============================================================
// 拓户准备数据类型
// ============================================================

/** 触达渠道 */
export interface ContactChannel {
  type: string;
  relation: string;
  contact_method: string;
  priority: 'high' | 'medium';
}

/** 拓户准备结果 */
export interface OutreachResult {
  action: 'result' | 'ambiguous' | 'not_found';
  credit_code?: string;
  company_name?: string;
  business_address?: string;
  registered_address?: string;
  contact_channels?: ContactChannel[];
  insights_h5_url?: string;
  script_h5_url?: string;
  channel_count?: number;
  message?: string;
  keyword?: string;
  options?: RiskAmbiguousOption[];
}

// ============================================================
// 产品智荐数据类型
// ============================================================

/** 推荐产品 */
export interface RecommendedProduct {
  product_name: string;
  category: string;
  priority: 'high' | 'medium' | 'low';
  priority_label: string;
  reason: string;
  expected_amount: string;
  features: string[];
  application_period: string;
}

/** 产品推荐结果 */
export interface ProductRecommendResult {
  action: 'result' | 'ambiguous' | 'not_found';
  credit_code?: string;
  company_name?: string;
  analysis_summary?: string;
  products?: RecommendedProduct[];
  detail_h5_url?: string;
  total_count?: number;
  message?: string;
  keyword?: string;
  options?: RiskAmbiguousOption[];
}

// ============================================================
// 产品智能匹配数据类型
// ============================================================

/** 匹配产品 */
export interface MatchedProduct {
  product_key: string;
  product_name: string;
  category: string;
  match_score: number;
  reason: string;
  highlights: string[];
  estimated_return: string;
  features: string[];
  application_period: string;
}

/** 产品智能匹配结果 */
export interface ProductMatchResult {
  action: 'result';
  needs_summary: string;
  needs_detail?: Record<string, unknown>;
  matches: MatchedProduct[];
  company_name?: string;
  credit_code?: string;
  total_count: number;
}

// ============================================================
// 对公账户开户数据类型
// ============================================================

/** 开户申请结果 */
export interface AccountOpeningResult {
  action: 'result' | 'ambiguous' | 'not_found';
  app_id?: string;
  company_name?: string;
  credit_code?: string;
  status?: 'upload' | 'processing' | 'preview' | 'submitted';
  upload_url?: string;
  preview_url?: string;
  submitted_url?: string;
  required_documents?: string[];
  message?: string;
  keyword?: string;
  options?: RiskAmbiguousOption[];
}

// ============================================================
// SSE 事件类型定义
// ============================================================

/** SSE 事件类型 */
export type SSEEventType =
  | 'meta'
  | 'text_start'
  | 'text_delta'
  | 'text_done'
  | 'potential_customer_summary'
  | 'potential_customer_detail'
  | 'risk_check_result'
  | 'outreach_result'
  | 'product_recommend_result'
  | 'product_match_result'
  | 'account_opening_result'
  | 'follow_up_suggestion'
  | 'done'
  | 'error';

/** SSE 事件数据 */
export interface SSEEvent {
  type: SSEEventType;
  content?: string;
  message_id?: string;
  conversation_id?: string;
  data?: PotentialCustomerSummary | PotentialCustomerDetail | RiskCheckResult | OutreachResult | ProductRecommendResult | ProductMatchResult | AccountOpeningResult;
}

// ============================================================
// 前端本地消息类型（用于流式渲染）
// ============================================================

/** 流式状态消息 */
export interface StreamingMessage {
  id: string;
  role: 'assistant';
  content: string;
  isStreaming: boolean;
  created_at: string;
  extra?: Record<string, unknown>;
}

/** 聊天消息联合类型 */
export type ChatMessage = Message | StreamingMessage;

/** 判断是否为流式消息 */
export function isStreamingMessage(
  msg: ChatMessage
): msg is StreamingMessage {
  return 'isStreaming' in msg && msg.isStreaming === true;
}
