#!/usr/bin/env node
// ============================================================
// 意图识别准确率自动化评测脚本
// 读取 intent_testset.json，调用 POST /api/chat/stream 批量评测，
// 从 SSE 事件推断 routeIntent 决策，输出准确率报告（终端 + report.json + report.md）
//
// 用法:
//   node scripts/evaluate-intent.mjs [options]
//
// 选项:
//   --base-url <url>        后端地址，默认 http://localhost:8000
//   --username <name>       登录已有账号（与 --password 一起传）
//   --password <pwd>        密码
//   --bank <name>           银行机构（登录/注册用），默认 "评测用"
//   --concurrency <n>       并发数，默认 3
//   --timeout <ms>          单条用例超时，默认 90000
//   --testset <path>        测试集路径，默认 backend/src/test/resources/intent_testset.json
//   --out-dir <path>        报告输出目录，默认 scripts/eval-output
//   --only-id <id[,id...]>  只跑指定用例 id（调试用）
//   --only-category <cat>   只跑指定 category（调试用）
//   --no-abort              不提前中断 SSE 流（读完整流，更慢但事件更全）
//   --dry-run               不请求后端，只输出测试集统计
// ============================================================

import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ---------- CLI 参数 ----------
const args = process.argv.slice(2);
const opt = { baseUrl: 'http://localhost:8000', concurrency: 3, timeout: 90000,
              bank: '评测用', testset: join(ROOT, 'backend/src/test/resources/intent_testset.json'),
              outDir: join(ROOT, 'scripts/eval-output'), abort: true };
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  const val = () => args[++i];
  switch (a) {
    case '--base-url': opt.baseUrl = val(); break;
    case '--username': opt.username = val(); break;
    case '--password': opt.password = val(); break;
    case '--bank': opt.bank = val(); break;
    case '--concurrency': opt.concurrency = parseInt(val(), 10) || 3; break;
    case '--timeout': opt.timeout = parseInt(val(), 10) || 90000; break;
    case '--testset': opt.testset = resolve(ROOT, val()); break;
    case '--out-dir': opt.outDir = resolve(ROOT, val()); break;
    case '--only-id': opt.onlyId = val().split(',').map(s => s.trim()).filter(Boolean); break;
    case '--only-category': opt.onlyCategory = val().trim(); break;
    case '--no-abort': opt.abort = false; break;
    case '--dry-run': opt.dryRun = true; break;
    case '--help':
      console.log(`意图识别准确率评测脚本

用法: node scripts/evaluate-intent.mjs [options]
  --base-url <url>        后端地址（默认 http://localhost:8000）
  --username <name>       登录已有账号（与 --password 一起传；不传则自动注册临时账号）
  --password <pwd>        密码
  --bank <name>           银行机构（默认 "评测用"）
  --concurrency <n>       并发数（默认 3）
  --timeout <ms>          单条用例超时 ms（默认 90000）
  --testset <path>        测试集路径（默认 backend/src/test/resources/intent_testset.json）
  --out-dir <path>        报告输出目录（默认 scripts/eval-output）
  --only-id <id,...>      只跑指定用例
  --only-category <cat>   只跑指定 category
  --no-abort              不提前中断 SSE 流
  --dry-run               只统计测试集，不请求后端`);
      process.exit(0);
  }
}

const SKILLS = new Set([
  'check_company_risk', 'query_company_basic_info', 'query_shareholder_info',
  'query_beneficiary_info', 'query_company_genealogy', 'query_customs_auth',
  'query_customs_blacklist', 'query_account_freeze_tag', 'query_credit_granting',
  'query_pboc_account_control', 'verify_business_license', 'generate_report',
  'query_due_diligence_reports',
]);
// 携带 _skill_name 的技能结果事件类型（决策可判定信号）
const SKILL_EVENT_TYPES = new Set([
  'risk_check_result', 'company_query_result', 'information_check_result',
  'historical_dd_query_result', 'potential_customer_summary', 'potential_customer_detail',
  'company_name_candidates',
]);

// ---------- 工具 ----------
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ---------- 认证 ----------
async function auth() {
  const headers = { 'Content-Type': 'application/json' };
  if (opt.username) {
    const res = await fetch(`${opt.baseUrl}/api/auth/login`, {
      method: 'POST', headers,
      body: JSON.stringify({ username: opt.username, password: opt.password, bankInstitution: opt.bank }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`登录失败 (HTTP ${res.status}): ${body}`);
    }
    const data = await res.json();
    return data.accessToken || data.token;
  }
  const username = `eval_${Date.now()}`;
  const password = `eval_${Math.random().toString(36).slice(2, 10)}`;
  const res = await fetch(`${opt.baseUrl}/api/auth/register`, {
    method: 'POST', headers,
    body: JSON.stringify({ username, password, bankInstitution: opt.bank }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`注册账号失败 (HTTP ${res.status}): ${body}`);
  }
  const data = await res.json();
  console.log(`[auth] 已自动注册临时账号: ${username}`);
  return data.accessToken || data.token;
}

// ---------- SSE 请求 + 决策推断 ----------
function parseExpected(expected) {
  if (expected.startsWith('multi[')) {
    return { kind: 'multi', skills: expected.slice(6, -1).split(',').map(s => s.trim()) };
  }
  return { kind: expected === 'chat' || expected === 'clarify' ? expected : 'skill', skill: expected };
}

/**
 * 发送一条消息并读取 SSE 流，返回 { decision, events }
 * decision: { type: 'skill', skill } | { type: 'chat' } | { type: 'clarify' }
 *           | { type: 'multi', skills } | { type: 'skill-unknown' }
 *           | { type: 'skill-unknown-multi', skills } | { type: 'unknown' }
 */
async function sendAndInfer(message, token) {
  const events = [];
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), opt.timeout);

  // 已收到决策信号时提前中断，节省流式时间（chat 长文本/技能后续事件无需读完）
  let decided = null;
  const maybeDecide = () => {
    if (decided) return decided;
    if (events.some(e => e.type === 'intent_candidates')) {
      decided = { type: 'clarify' };
      return decided;
    }
    const skillEvts = events.filter(e => e._skill_name);
    const multiEvts = skillEvts.filter(e => e._multi_index !== undefined && e._multi_index !== null);
    if (skillEvts.length > 0 && multiEvts.length === 0) {
      decided = { type: 'skill', skill: skillEvts[skillEvts.length - 1]._skill_name };
      return decided;
    }
    if (multiEvts.length >= 2) {
      decided = {
        type: 'multi',
        skills: multiEvts.sort((a, b) => a._multi_index - b._multi_index).map(e => e._skill_name),
      };
      return decided;
    }
    if (events.some(e => e.type === 'text_start')) {
      decided = { type: 'chat' };
      return decided;
    }
    return null;
  };

  try {
    const res = await fetch(`${opt.baseUrl}/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ message, conversationId: null }),
      signal: controller.signal,
    });
    if (!res.ok) {
      return { decision: { type: 'error', status: res.status }, events };
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || trimmed === '[DONE]') continue;
        // 兼容 "data: {json}" / "data:{json}" / 裸 JSON 三种格式
        let jsonStr = trimmed;
        if (trimmed.startsWith('data: ')) jsonStr = trimmed.slice(6);
        else if (trimmed.startsWith('data:')) jsonStr = trimmed.slice(5).trim();
        if (!jsonStr.startsWith('{')) continue;
        try {
          const ev = JSON.parse(jsonStr);
          events.push(ev);
        } catch { /* 非 JSON 行忽略 */ }
      }
      if (opt.abort) {
        const d = maybeDecide();
        if (d) { controller.abort(); return { decision: d, events }; }
      }
    }
  } catch (err) {
    if (err.name === 'AbortError' && opt.abort) {
      // 主动中断后尝试再次判定
      const d = maybeDecide();
      if (d) return { decision: d, events };
    }
    return { decision: { type: 'error', message: String(err?.message || err) }, events };
  } finally {
    clearTimeout(timeoutId);
  }

  // 流自然结束（读到 done / EOF）后的兜底判定
  const d = maybeDecide();
  if (d) return { decision: d, events };
  if (events.some(e => e.type === 'text_delta' || e.type === 'text_done')) {
    // 无 text_start 的文本事件 → 技能 info_needed / error，技能名无法从 SSE 推断
    return { decision: { type: 'skill-unknown' }, events };
  }
  return { decision: { type: 'unknown' }, events };
}

// ---------- 判定命中 ----------
function isHit(expected, actual) {
  const exp = parseExpected(expected);
  if (exp.kind === 'skill') return actual.type === 'skill' && actual.skill === exp.skill;
  if (exp.kind === 'chat') return actual.type === 'chat';
  if (exp.kind === 'clarify') return actual.type === 'clarify';
  if (exp.kind === 'multi') {
    return actual.type === 'multi' && JSON.stringify(actual.skills) === JSON.stringify(exp.skills);
  }
  return false;
}

function actualLabel(actual) {
  switch (actual.type) {
    case 'skill': return `skill:${actual.skill}`;
    case 'chat': return 'chat';
    case 'clarify': return 'clarify';
    case 'multi': return `multi:[${actual.skills.join(',')}]`;
    case 'skill-unknown': return 'skill:<未知技能> (info_needed/error)';
    case 'skill-unknown-multi': return `multi(部分):[${actual.skills.join(',')}]`;
    case 'error': return `error:${actual.status || actual.message || ''}`;
    default: return 'unknown';
  }
}

// ---------- 评测执行 ----------
async function evaluate() {
  const raw = JSON.parse(readFileSync(opt.testset, 'utf-8'));
  let cases = raw.cases;
  if (opt.onlyId) cases = cases.filter(c => opt.onlyId.includes(c.id));
  if (opt.onlyCategory) cases = cases.filter(c => c.category === opt.onlyCategory);
  if (cases.length === 0) { console.error('[fatal] 筛选后无用例'); process.exit(1); }

  console.log(`测试集: ${raw.metadata.name} v${raw.metadata.version} | 评测用例: ${cases.length}/${raw.cases.length}`);

  if (opt.dryRun) {
    const cnt = (k) => cases.reduce((m, c) => (m[c[k]] = (m[c[k]] || 0) + 1, m), {});
    console.log('level 分布:', JSON.stringify(cnt('level')));
    console.log('category 分布:', JSON.stringify(cnt('category')));
    return;
  }

  console.log(`后端: ${opt.baseUrl} | 并发: ${opt.concurrency} | 超时: ${opt.timeout}ms`);
  const token = await auth();

  const results = [];
  let cursor = 0;
  const worker = async () => {
    while (cursor < cases.length) {
      const idx = cursor++;
      const c = cases[idx];
      const convId = `${c.id}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
      try {
        // context 用例：先在同一会话发送前置消息（prelude），让上下文记忆生效
        if (Array.isArray(c.prelude) && c.prelude.length > 0) {
          for (const p of c.prelude) {
            await sendAndInfer(p, token);
          }
        }
        const { decision, events } = await sendAndInfer(c.input, token);
        const hit = isHit(c.expected, decision);
        results.push({ id: c.id, input: c.input, expected: c.expected, level: c.level,
          category: c.category, note: c.note || '', hit, actual: decision, events });
        process.stdout.write(`  [${results.length}/${cases.length}] ${c.id} ${hit ? '✓' : '✗'} expected=${c.expected} actual=${actualLabel(decision)}\n`);
      } catch (err) {
        results.push({ id: c.id, input: c.input, expected: c.expected, level: c.level,
          category: c.category, note: c.note || '', hit: false,
          actual: { type: 'error', message: String(err?.message || err) }, events: [] });
        process.stdout.write(`  [${results.length}/${cases.length}] ${c.id} ✗ error=${err?.message}\n`);
      }
    }
  };
  const workers = Array.from({ length: Math.min(opt.concurrency, cases.length) }, () => worker());
  await Promise.all(workers);

  // ---------- 统计 ----------
  const needReview = (r) => r.actual.type === 'skill-unknown' || r.actual.type === 'skill-unknown-multi'
    || (r.actual.type === 'error' && !r.hit);
  const hits = results.filter(r => r.hit).length;
  const misses = results.filter(r => !r.hit).length;
  const reviews = results.filter(needReview).length;

  const pct = (n, d) => d === 0 ? '-' : `${(100 * n / d).toFixed(1)}%`;
  const groupBy = (key) => {
    const map = new Map();
    for (const r of results) {
      if (!map.has(r[key])) map.set(r[key], []);
      map.get(r[key]).push(r);
    }
    return [...map.entries()].map(([k, list]) => ({
      key: k, total: list.length, hit: list.filter(x => x.hit).length,
      acc: pct(list.filter(x => x.hit).length, list.length),
      miss: list.filter(x => !x.hit).map(x => x.id),
    }));
  };

  const byLevel = groupBy('level');
  const byCategory = groupBy('category');
  const bySkill = groupBy('expected').filter(g => SKILLS.has(g.key));

  // 混淆矩阵
  const confRows = [...new Set(results.map(r => r.expected))].sort();
  const confCols = [...new Set(results.map(r => actualLabel(r.actual)))].sort();
  const confMap = new Map();
  for (const r of results) {
    const key = `${r.expected}||${actualLabel(r.actual)}`;
    confMap.set(key, (confMap.get(key) || 0) + 1);
  }

  const timestamp = new Date().toISOString();
  const report = {
    meta: {
      generated_at: timestamp, base_url: opt.baseUrl, username: opt.username || '(auto-registered)',
      testset: raw.metadata.name, version: raw.metadata.version, total_cases: cases.length,
      concurrency: opt.concurrency, timeout_ms: opt.timeout, aborted_early: opt.abort,
      eval_target: raw.metadata.eval_target,
    },
    summary: { total: results.length, hit: hits, miss: misses, accuracy: pct(hits, results.length),
               need_review: reviews },
    by_level: byLevel, by_category: byCategory, by_skill: bySkill,
    confusion: confRows.map(exp => ({ expected: exp, row: confCols.map(col => ({ actual: col, count: confMap.get(`${exp}||${col}`) || 0 })) })),
    need_review_list: results.filter(needReview).map(r => ({ id: r.id, input: r.input, expected: r.expected, actual: actualLabel(r.actual), note: r.note })),
    failures: results.filter(r => !r.hit).map(r => ({
      id: r.id, input: r.input, expected: r.expected, level: r.level, category: r.category,
      actual: actualLabel(r.actual), events: r.events.map(e => e.type).join(','),
    })),
  };

  // ---------- 输出 ----------
  const L = console.log;
  L('\n' + '='.repeat(60));
  L('意图识别准确率评测报告');
  L('='.repeat(60));
  L(`时间: ${timestamp} | 后端: ${opt.baseUrl} | 用例: ${report.summary.total}`);
  L(`整体准确率: ${report.summary.hit}/${report.summary.total} = ${report.summary.accuracy}`);
  L(`未命中: ${report.summary.miss} | 需人工复核: ${report.summary.need_review}`);
  L('\n--- 分难度 ---');
  for (const g of byLevel) L(`  ${g.key.padEnd(7)} ${g.hit}/${g.total} = ${g.acc}`);
  L('\n--- 分类型 ---');
  for (const g of byCategory) L(`  ${g.key.padEnd(18)} ${g.hit}/${g.total} = ${g.acc}`);
  L('\n--- 分技能 (expected=技能名) ---');
  for (const g of bySkill) L(`  ${g.key.padEnd(30)} ${g.hit}/${g.total} = ${g.acc}`);
  L('\n--- 混淆矩阵 (expected \\ actual) ---');
  const colW = Math.max(...confCols.map(c => c.length), 16) + 2;
  L('  ' + 'expected'.padEnd(34) + confCols.map(c => c.slice(0, colW - 2).padEnd(colW)).join(''));
  for (const exp of confRows) {
    const row = confCols.map(col => String(confMap.get(`${exp}||${col}`) || 0).padStart(colW));
    L('  ' + exp.padEnd(34) + row.join(''));
  }
  if (report.failures.length > 0) {
    L('\n--- 未命中明细 (前 30 条) ---');
    for (const f of report.failures.slice(0, 30)) {
      L(`  ${f.id} expected=${f.expected} actual=${f.actual} | ${f.input}`);
    }
    if (report.failures.length > 30) L(`  ... 其余 ${report.failures.length - 30} 条见报告文件`);
  }

  mkdirSync(opt.outDir, { recursive: true });
  writeFileSync(join(opt.outDir, 'report.json'), JSON.stringify(report, null, 2), 'utf-8');
  writeFileSync(join(opt.outDir, 'report.md'), buildMarkdown(report), 'utf-8');
  L(`\n报告已输出: ${opt.outDir}/report.json 和 report.md`);
}

function buildMarkdown(report) {
  const rows = (list) => list.map(g => `| ${g.key} | ${g.total} | ${g.hit} | ${g.acc} |`).join('\n');
  const md = [];
  md.push('# 意图识别准确率评测报告\n');
  md.push(`- 生成时间: ${report.meta.generated_at}`);
  md.push(`- 后端地址: ${report.meta.base_url}`);
  md.push(`- 账号: ${report.meta.username}`);
  md.push(`- 测试集: ${report.meta.testset} v${report.meta.version}`);
  md.push(`- 评测口径: ${report.meta.eval_target}`);
  md.push(`- 并发/超时: ${report.meta.concurrency} / ${report.meta.timeout_ms}ms`);
  md.push(`- 提前中断 SSE: ${report.meta.aborted_early}\n`);
  md.push('## 总览\n');
  md.push('| 指标 | 值 |');
  md.push('| --- | --- |');
  md.push(`| 总用例 | ${report.summary.total} |`);
  md.push(`| 命中 | ${report.summary.hit} |`);
  md.push(`| 未命中 | ${report.summary.miss} |`);
  md.push(`| 整体准确率 | ${report.summary.accuracy} |`);
  md.push(`| 需人工复核 | ${report.summary.need_review} |\n`);
  md.push('## 分难度准确率\n');
  md.push('| 难度 | 用例数 | 命中 | 准确率 |\n| --- | --- | --- | --- |');
  md.push(rows(report.by_level) + '\n');
  md.push('## 分类型准确率\n');
  md.push('| 类型 | 用例数 | 命中 | 准确率 |\n| --- | --- | --- | --- |');
  md.push(rows(report.by_category) + '\n');
  md.push('## 分技能准确率（expected 为技能名）\n');
  md.push('| 技能 | 用例数 | 命中 | 准确率 |\n| --- | --- | --- | --- |');
  md.push(rows(report.by_skill) + '\n');
  md.push('## 混淆矩阵\n');
  md.push('| expected \\ actual | ' + report.confusion[0]?.row.map(r => r.actual).join(' | ') + ' |');
  md.push('|' + ' --- |'.repeat(report.confusion[0]?.row.length + 1 || 2));
  for (const c of report.confusion) {
    md.push(`| ${c.expected} | ` + c.row.map(r => r.count).join(' | ') + ' |');
  }
  md.push('');
  if (report.need_review_list.length > 0) {
    md.push('## 需人工复核清单\n');
    md.push('> 以下用例实际已路由到技能，但技能名无法从 SSE 事件精确推断（技能进入缺参询问/出错分支，或 multi 管道部分执行），请结合后端日志 `Coordinator routed to skill` 复核。\n');
    md.push('| id | input | expected | actual | note |\n| --- | --- | --- | --- | --- |');
    for (const r of report.need_review_list) {
      md.push(`| ${r.id} | ${r.input} | ${r.expected} | ${r.actual} | ${r.note} |`);
    }
    md.push('');
  }
  if (report.failures.length > 0) {
    md.push('## 未命中明细\n');
    md.push('| id | input | expected | actual | level | category | 事件序列 |\n| --- | --- | --- | --- | --- | --- | --- |');
    for (const f of report.failures) {
      md.push(`| ${f.id} | ${f.input} | ${f.expected} | ${f.actual} | ${f.level} | ${f.category} | ${f.events} |`);
    }
    md.push('');
  }
  md.push('## 评测局限说明\n');
  md.push('- 本报告通过 HTTP 接口评测，混入了技能执行与上下文记忆等下游环节的影响，非纯 routeIntent 单元评测。');
  md.push('- 技能进入缺参询问（info_needed）或执行出错时，SSE 仅返回文本事件，无法推断具体技能名，此类用例计入"需人工复核"。');
  md.push('- multi 意图若第一个任务即缺参暂停，后续任务不会执行，只能从事件推断部分技能列表。');
  md.push('- adversarial 类用例含系统已知缺陷点（见测试集 note），未命中不一定是回归，请对照缺陷清单人工复核。');
  return md.join('\n');
}

evaluate().catch(err => { console.error('[fatal]', err); process.exit(1); });
