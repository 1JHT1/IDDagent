// 死循环修复验证脚本：模拟用户输入序列，检查不再弹卡片死循环
const TOKEN = process.env.TOKEN;
const BASE = 'http://localhost:8000/api';

async function chat(message, conversationId) {
  const res = await fetch(`${BASE}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${TOKEN}` },
    body: JSON.stringify({ message, conversationId, attachments: [] }),
  });
  const text = await res.text();
  const events = [];
  for (const block of text.split('\n\n')) {
    const line = block.trim();
    if (!line) continue;
    try { events.push(JSON.parse(line)); } catch (e) { /* ignore */ }
  }
  return events;
}

function summarize(events) {
  const types = events.map(e => {
    if (e.type === 'company_name_candidates') {
      return `company_name_candidates(action=${e.action}, options=${(e.options || []).length}家: ${(e.options || []).map(o => o.company_name).join('/')})`;
    }
    if (e.type === 'information_check_result') return `information_check_result(action=${e.action})`;
    if (e.type === 'text_delta') return `text_delta: ${(e.content || '').slice(0, 60)}`;
    if (e.type === 'text_done') return `text_done`;
    return e.type;
  });
  return types.join(' | ');
}

(async () => {
  // ===== 场景1：完整企业名（修复3验证：源头不弹卡片）=====
  console.log('\n===== 场景1: "帮我核实小米科技有限公司的信息"（新会话）=====');
  let events = await chat('帮我核实小米科技有限公司的信息', undefined);
  let doneEvt = events.find(e => e.type === 'done');
  let convId = doneEvt ? doneEvt.conversation_id : null;
  console.log('conversation_id:', convId);
  console.log('事件流:', summarize(events));
  const hasAmbiguous1 = events.some(e => e.type === 'company_name_candidates' || e.type === 'information_check_result' && e.action === 'ambiguous');
  console.log('>>> 是否弹候选卡片(应为false):', hasAmbiguous1);

  // ===== 场景2：简称（修复1验证：ambiguous → company_name_candidates + pendingSkill）=====
  console.log('\n===== 场景2: "帮我核实小米科技的信息"（同会话，简称）=====');
  events = await chat('帮我核实小米科技的信息', convId);
  console.log('事件流:', summarize(events));
  const candidatesEvt = events.find(e => e.type === 'company_name_candidates');
  console.log('>>> 收到候选卡片事件(应为true):', !!candidatesEvt);
  const opts = candidatesEvt ? candidatesEvt.options : [];

  // ===== 场景3：点击卡片选项（模拟 CompanyNameSelector 发送格式，修复1/2验证：不再循环）=====
  console.log('\n===== 场景3: 点击候选"小米科技有限公司"（带信用代码）=====');
  if (opts.length > 0) {
    const opt = opts.find(o => o.company_name === '小米科技有限公司') || opts[0];
    events = await chat(`公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`, convId);
    console.log('事件流:', summarize(events));
    const again = events.some(e => e.type === 'company_name_candidates');
    const infoNeeded = events.some(e => e.type === 'text_delta');
    console.log('>>> 再次弹候选卡片(应为false):', again);
    console.log('>>> 提示补充信息(应为true):', infoNeeded);
  } else {
    console.log('!!! 场景2未返回候选，跳过场景3');
  }

  // ===== 场景4：华为（修复3对简称/非索引名的处理）=====
  console.log('\n===== 场景4: "核实华为公司信息"（新会话）=====');
  events = await chat('核实华为公司信息', undefined);
  console.log('事件流:', summarize(events));

  // ===== 场景5：云禾（回归验证，应与修复前一致）=====
  console.log('\n===== 场景5: "核实云禾科技信息"（新会话，回归）=====');
  events = await chat('核实云禾科技信息', undefined);
  console.log('事件流:', summarize(events));
  console.log('\n========== 测试完成 ==========');
})();
