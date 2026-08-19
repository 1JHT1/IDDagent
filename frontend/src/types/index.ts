// ============================================================
// 消息相关类型定义
// ============================================================

/** 消息角色 */
export type MessageRole = 'user' | 'assistant';

/** 聊天附件 */
export interface ChatAttachment {
  /** 文件名 */
  name: string;
  /** 访问地址 */
  url: string;
  /** 文件大小（字节） */
  size: number;
  /** MIME 类型 */
  type: string;
  /** 后端生成的文件 ID */
  file_id?: string;
}

/** 单条消息 */
export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  created_at: string;
  /** 结构化额外数据（潜客卡片等） */
  extra?: Record<string, unknown>;
  /** 消息附件 */
  attachments?: ChatAttachment[];
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
// 报告生成数据类型
// ============================================================

/** 报告模板 */
export interface ReportTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  accepted_types: string[];
  required_fields: string[];
  output_type: string;
  source_file: string;
}

/** 报告生成结果 */
export interface ReportGenerateResult {
  action: 'result' | 'not_found';
  _skill_name: 'generate_report';
  stage: 'templates' | 'upload' | 'generating' | 'done';
  report_id?: string;
  message?: string;

  // stage=templates
  templates?: ReportTemplate[];

  // stage=upload
  template_id?: string;
  template_name?: string;
  template_icon?: string;
  template_description?: string;
  required_fields?: string[];
  accepted_types?: string[];

  // stage=generating
  status?: 'generating' | 'completed' | 'failed';
  progress?: number;
  company_name?: string;

  // stage=done
  report_name?: string;
  view_url?: string;
  download_url?: string;
}

// ============================================================
// 信息核实数据类型
// ============================================================

/** 信息核实结果 */
export interface InformationCheckResult {
  action: 'result' | 'ambiguous' | 'not_found';
  credit_code?: string;
  company_name?: string;
  details_name?: string;
  total_count?: number;
  pass_count?: number;
  fail_count?: number;
  none_count?: number;
  h5_url?: string;
  message?: string;
  keyword?: string;
  options?: RiskAmbiguousOption[];
}

// ============================================================
// 历史尽调报告查询数据类型
// ============================================================

/** 历史尽调报告记录 */
export interface HistoricalDDReport {
  report_id: string;
  institution: string;
  company_name: string;
  name: string;
  template_type: string;
  status: 'completed' | 'incomplete';
  status_label: string;
  created_at: string;
  updated_at: string;
  /** 附件列表（含 file_id / file_name） */
  attachments?: { file_id: string; file_name: string }[];
}

/** 历史尽调查询结果 */
export interface HistoricalDDQueryResult {
  action: 'result' | 'need_date_range' | 'not_found';
  company_name?: string;
  credit_code?: string;
  total_count: number;
  query_params?: {
    date_from?: string;
    date_to?: string;
  };
  records?: HistoricalDDReport[];
  message?: string;
}

/** 企业名称候选 */
export interface CompanyNameCandidate {
  credit_code: string;
  company_name: string;
}

/** 企业名称候选事件数据 */
export interface CompanyNameCandidatesData {
  action: 'candidates';
  keyword: string;
  message: string;
  options: CompanyNameCandidate[];
  /** 所属任务标识（多意图管道中让用户清楚"是哪个任务在询问"） */
  task_label?: string;
  /** 查询功能标签（二次点击协议：帮我查一下{公司名}{query_label}） */
  query_label?: string;
  /** 已确认过候选（后端落盘 + 前端乐观更新） */
  confirmed?: boolean;
}

// ============================================================
// 意图澄清（Phase 4）与任务规划进度数据类型
// ============================================================

/** 澄清选项 */
export interface ClarificationOption {
  label: string;
  value: string;
}

/** 意图澄清事件数据（同技能多主体冲突时让用户确认执行对象） */
export interface ClarificationData {
  action: 'clarification';
  question: string;
  options: ClarificationOption[];
  context?: {
    skill: string;
    params: Record<string, unknown>;
  };
}

// ============================================================
// 任务规划状态类型（plan_status 事件 / GET /api/plan/{id}/status）
// ============================================================

/** 规划步骤状态（与后端 ContextMemoryService.PlanStatus 一致） */
export type PlanStepStatus = 'PENDING' | 'WAITING_INPUT' | 'RUNNING' | 'DONE' | 'FAILED' | 'WAITING_EXTERNAL';

/** 规划步骤快照 */
export interface PlanStatusStep {
  skill: string;
  summary?: string;
  status: PlanStepStatus;
  needsInput?: boolean;
}

/** 规划状态数据（plan_status 事件 data / GET /api/plan/{id}/status 返回的 plan 字段） */
export interface PlanStatusData {
  active: boolean;
  steps: PlanStatusStep[];
  index: number;
  confirming: boolean;
  suspended: boolean;
  planId?: string;
  /** 收尾汇总文本（全部完成/提前结束的总结文案，面板终态展示） */
  summary?: string;
}

// ============================================================
// SSE 事件类型定义
// ============================================================

/** SSE 事件类型 */
export type SSEEventType =
  | 'thinking'
  | 'meta'
  | 'text_start'
  | 'text_delta'
  | 'text_done'
  | 'risk_check_result'
  | 'report_generate_result'
  | 'information_check_result'
  | 'historical_dd_query_result'
  | 'company_query_result'
  | 'follow_up_suggestion'
  | 'company_name_candidates'
  | 'need_date_range'
  | 'clarification'
  | 'plan_progress'
  | 'plan_preview'
  | 'plan_summary'
  | 'plan_step_confirm'
  | 'plan_status'
  | 'resume_confirm'
  | 'done'
  | 'error';

/** SSE 事件数据 */
export interface SSEEvent {
  type: SSEEventType;
  content?: string;
  message_id?: string;
  conversation_id?: string;
  data?: PotentialCustomerSummary | PotentialCustomerDetail | RiskCheckResult | ReportGenerateResult | InformationCheckResult | HistoricalDDQueryResult | CompanyNameCandidatesData | ClarificationData | PlanStatusData;
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
  /** 消息附件 */
  attachments?: ChatAttachment[];
}

/** 聊天消息联合类型 */
export type ChatMessage = Message | StreamingMessage;

/** 判断是否为流式消息 */
export function isStreamingMessage(
  msg: ChatMessage
): msg is StreamingMessage {
  return 'isStreaming' in msg && msg.isStreaming === true;
}
