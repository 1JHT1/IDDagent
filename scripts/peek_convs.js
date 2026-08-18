const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'data', 'conversations.json');
const raw = fs.readFileSync(file, 'utf8');
const data = JSON.parse(raw);
console.log('顶层keys:', Object.keys(data));
for (const k of Object.keys(data)) {
  const v = data[k];
  if (Array.isArray(v)) console.log(`key=${k} 是数组, 长度=${v.length}`);
  else if (v && typeof v === 'object') console.log(`key=${k} 是对象, 子keys=${Object.keys(v).slice(0, 5).join(',')}`);
  else console.log(`key=${k} 是${typeof v}`);
}
