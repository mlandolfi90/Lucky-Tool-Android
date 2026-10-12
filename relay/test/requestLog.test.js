import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  RPC_SNIFF_MAX_BYTES,
  formatRequestLog,
  rpcSummary,
  safePath,
  shortId,
} from '../src/requestLog.js';

const jsonBody = (value) => ({
  method: 'POST',
  pathname: '/mcp',
  contentType: 'application/json; charset=utf-8',
  body: Buffer.from(typeof value === 'string' ? value : JSON.stringify(value), 'utf8'),
});

const baseLine = {
  rid: '3f2a9c1e',
  method: 'POST',
  path: '/mcp',
  rpc: 'tools/call',
  tool: null,
  status: 200,
  reqBytes: 10,
  resBytes: 20,
  inflight: 0,
  idleMs: 5,
  sentAt: 1_791_700_000_123,
  bodyMs: 0.4,
  deviceMs: 1.2,
  totalMs: 2.0,
  aborted: false,
};

describe('requestLog', () => {
  it('shortId keeps the first 8 characters', () => {
    assert.equal(shortId('3f2a9c1e-7b2d-4a11-9c3e-6f0a1b2c3d4e'), '3f2a9c1e');
  });

  it('safePath redacts capability links', () => {
    assert.equal(safePath('/s/abc'), '/s/<redacted>');
    assert.equal(safePath('/mcp'), '/mcp');
  });

  it('safePath truncates long paths', () => {
    const long = `/${'a'.repeat(299)}`;
    const result = safePath(long);
    assert.equal(result, `${long.slice(0, 128)}…`);
    assert.equal(result.length, 129);
  });

  it('rpcSummary reads method and tool of a tools/call', () => {
    const summary = rpcSummary(
      jsonBody('{"jsonrpc":"2.0","id":1,"method":"tools/call",' +
        '"params":{"name":"tap_node","arguments":{"text":"secret-arg"}}}'),
    );
    assert.deepEqual(summary, { rpc: 'tools/call', tool: 'tap_node' });
  });

  it('rpcSummary reports initialize without tool', () => {
    assert.deepEqual(rpcSummary(jsonBody({ method: 'initialize' })), { rpc: 'initialize', tool: null });
  });

  it('rpcSummary is not inspected without input', () => {
    assert.deepEqual(rpcSummary(null), { rpc: '-', tool: null });
  });

  it('rpcSummary ignores other paths and methods', () => {
    for (const pathname of ['/token', '/register']) {
      assert.equal(rpcSummary({ ...jsonBody({ method: 'tools/list' }), pathname }).rpc, '-');
    }
    assert.equal(rpcSummary({ ...jsonBody({ method: 'tools/list' }), method: 'GET' }).rpc, '-');
  });

  it('rpcSummary ignores non-JSON content types', () => {
    for (const contentType of ['text/plain', 'application/x-www-form-urlencoded', undefined]) {
      assert.equal(rpcSummary({ ...jsonBody({ method: 'tools/list' }), contentType }).rpc, '-');
    }
  });

  it('rpcSummary skips bodies over the sniff limit', () => {
    const summary = rpcSummary({ ...jsonBody({ method: 'tools/list' }), body: Buffer.alloc(RPC_SNIFF_MAX_BYTES + 1) });
    assert.equal(summary.rpc, '-');
  });

  it('rpcSummary maps invalid JSON to ?', () => {
    assert.deepEqual(rpcSummary(jsonBody('{')), { rpc: '?', tool: null });
    assert.deepEqual(rpcSummary(jsonBody('null')), { rpc: '?', tool: null });
  });

  it('rpcSummary rejects unsafe method and tool names', () => {
    assert.equal(rpcSummary(jsonBody({ method: 'tools call' })).rpc, '?');
    assert.equal(rpcSummary(jsonBody({ method: 'tools\ncall' })).rpc, '?');
    assert.equal(rpcSummary(jsonBody({ method: 'a'.repeat(65) })).rpc, '?');
    assert.equal(rpcSummary(jsonBody({ method: 'tools/call', params: { name: 'a\nb' } })).tool, null);
  });

  it('rpcSummary reports batches', () => {
    assert.deepEqual(rpcSummary(jsonBody([{ method: 'tools/list' }])), { rpc: 'batch', tool: null });
  });

  it('formatRequestLog prints - for phases that did not run', () => {
    const line = formatRequestLog({
      ...baseLine,
      status: null,
      idleMs: null,
      sentAt: null,
      bodyMs: null,
      deviceMs: null,
    });
    assert.match(line, /status=-/);
    assert.match(line, /idleMs=-/);
    assert.match(line, /sentAt=-/);
    assert.match(line, /bodyMs=-/);
    assert.match(line, /deviceMs=-/);
  });

  it('formatRequestLog adds tool and aborted only when set', () => {
    const plain = formatRequestLog(baseLine);
    assert.equal(plain.includes('tool='), false);
    assert.equal(plain.includes('aborted=1'), false);
    const flagged = formatRequestLog({ ...baseLine, tool: 'x', aborted: true });
    assert.match(flagged, /tool=x/);
    assert.match(flagged, /aborted=1$/);
  });

  it('formatRequestLog writes one line with one-decimal durations', () => {
    const line = formatRequestLog({ ...baseLine, totalMs: 12.34 });
    assert.equal(line.includes('\n'), false);
    assert.match(line, /totalMs=12\.3/);
  });
});
