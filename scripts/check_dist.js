const fs = require('fs');
const p = 'd:/HuaweiMoveData/Users/蒋洪涛/Desktop/IDDagent/frontend/dist';

function walk(d, pre) {
  for (const f of fs.readdirSync(d)) {
    const fp = d + '/' + f;
    const st = fs.statSync(fp);
    console.log(pre + f, st.size, st.mtime.toLocaleString());
    if (st.isDirectory()) walk(fp, pre + '  ');
  }
}
walk(p, '');

const html = fs.readFileSync(p + '/index.html', 'utf8');
console.log('---index.html---');
console.log(html);

const m = html.match(/src="([^"]+\.js)"/);
if (m) {
  const jp = p + m[1];
  console.log('---js:', jp, '---');
  const c = fs.readFileSync(jp, 'utf8');
  console.log('len:', c.length);
  const checks = [
    'currentConversationId',
    'plan-status-',
    'report-complete',
    'conversationLoadSeq',
    'syncConversationId',
    'clearMessages',
    'plan_status',
  ];
  for (const k of checks) console.log('has ' + k + ':', c.includes(k));
  console.log('head:', c.slice(0, 300));
} else {
  console.log('no js src in index.html');
}
