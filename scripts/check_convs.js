const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'data', 'conversations.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
let total = 0;
for (const userId of Object.keys(data)) {
  const convMap = data[userId];
  const convIds = Object.keys(convMap);
  for (const cid of convIds) {
    const c = convMap[cid];
    const msgs = (c && c.messages) || [];
    const planMsgs = msgs.filter(m => m.extra && m.extra.action === 'plan_status');
    const planIds = [...new Set(planMsgs.map(m => m.extra.planId))];
    const reportMsgs = msgs.filter(m => m.extra && m.extra._skill_name === 'generate_report');
    console.log(`用户${userId.slice(0, 8)} 会话 ${cid} | 消息数:${msgs.length} | plan_status数:${planMsgs.length} | report卡数:${reportMsgs.length} | planIds: ${planIds.join(',')}`);
    total++;
  }
}
console.log('总会话数:', total);
