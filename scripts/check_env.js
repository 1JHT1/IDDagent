const { execSync } = require('child_process');
try {
  console.log('=== 监听端口 ===');
  const netstat = execSync('netstat -ano | findstr LISTENING', { encoding: 'utf8' });
  const lines = netstat.split(/\r?\n/).filter(l => l.trim());
  for (const l of lines) {
    const m = l.trim().match(/(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)/);
    if (m) {
      const port = m[2].split(':').pop();
      if (['3000', '5173', '8000', '8080', '8081', '9090'].includes(port)) {
        console.log(l.trim());
      }
    }
  }
  console.log('\n=== node/java 进程命令行 ===');
  const ps = execSync('wmic process where "name=\'node.exe\' or name=\'java.exe\'" get ProcessId,CommandLine /format:list', { encoding: 'utf8' });
  const entries = ps.split(/\r?\n\r?\n/);
  for (const e of entries) {
    const idm = e.match(/ProcessId=(\d+)/);
    const cm = e.match(/CommandLine=(.+)/s);
    if (idm && cm) {
      const cmd = cm[1].trim();
      if (cmd.length > 400) console.log(idm[1], ':', cmd.slice(0, 400), '...');
      else console.log(idm[1], ':', cmd);
    }
  }
} catch (err) {
  console.error('ERR:', err.message);
}
