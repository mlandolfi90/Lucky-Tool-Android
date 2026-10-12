import assert from 'node:assert/strict';
import { once } from 'node:events';
import { connect } from 'node:net';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { CLOSE_REPLACED } from '../src/relay.js';
import {
  TEST_TOKEN,
  connectDevice,
  nextFrame,
  recordingLogger,
  rejectedUpgradeStatus,
  respond,
  startRelay,
} from './helpers.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NO_FRAME = 'no frame';
const DEADLINE_MS = 2_000;
const POLL_MS = 20;
/** Headers of a POST that declares a 100-byte body; the body is written separately. */
const UPLOAD_HEADERS = 'POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nContent-Length: 100\r\n\r\n';
/** A POST whose declared body never fully arrives. */
const STALLED_UPLOAD = `${UPLOAD_HEADERS}partial`;

async function fetchText(url, init) {
  const res = await fetch(url, init);
  return { res, text: await res.text() };
}

/** Opens a raw connection, writes `text` and leaves the socket open (e.g. a request whose body never ends). */
function openSocket(port, text) {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1', () => {
      socket.write(text);
      resolve(socket);
    });
    socket.on('error', reject);
  });
}

/**
 * Resolves with everything the server sent on `socket` once the connection closes. A connection still open after
 * DEADLINE_MS is destroyed and the promise rejects with what had arrived, so a regression fails instead of hanging.
 */
function replyOf(socket) {
  return new Promise((resolve, reject) => {
    let data = '';
    const timer = setTimeout(() => {
      reject(new Error(`connection still open after ${DEADLINE_MS} ms, received ${JSON.stringify(data)}`));
      socket.destroy();
    }, DEADLINE_MS);
    socket.on('data', (chunk) => {
      data += chunk.toString('utf8');
    });
    // 'close' follows any error and settles with what arrived (e.g. a reset after the server already answered).
    socket.on('error', () => {});
    socket.on('close', () => {
      clearTimeout(timer);
      resolve(data);
    });
  });
}

/** Writes a raw HTTP/1.1 request and resolves with the server's whole reply (see `replyOf`). */
async function rawRequest(port, text) {
  return replyOf(await openSocket(port, text));
}

/** Resolves with the next frame of any type, or with NO_FRAME when none arrives within `ms`. */
function frameOrNothing(ws, ms) {
  return Promise.race([nextFrame(ws, null), sleep(ms, NO_FRAME)]);
}

/** Rejects when `promise` has not settled within `ms`, so a regression fails the test instead of hanging it. */
function withDeadline(promise, ms, what) {
  let timer;
  const deadline = new Promise((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out after ${ms} ms waiting for ${what}`)), ms);
  });
  return Promise.race([promise, deadline]).finally(() => clearTimeout(timer));
}

/** Polls `check` until it resolves truthy; fails after `ms` instead of hanging the test. */
async function eventually(check, ms, what) {
  const deadline = Date.now() + ms;
  while (!(await check())) {
    if (Date.now() > deadline) assert.fail(`timed out after ${ms} ms waiting for ${what}`);
    await sleep(POLL_MS);
  }
}

/** Resolves once `server` has dispatched `count` requests (the relay's own listener runs first: they are admitted). */
function requestsDispatched(server, count) {
  return new Promise((resolve) => {
    let seen = 0;
    const onRequest = () => {
      seen += 1;
      if (seen < count) return;
      server.off('request', onRequest);
      resolve();
    };
    server.on('request', onRequest);
  });
}

/** The per-request timing lines a recording logger captured. */
const timingLines = (logger) => logger.lines.filter((line) => line.startsWith('relay: request '));

/** Numeric value of `field` in a timing line, or null when the field printed `-`. */
function numberOf(line, field) {
  const match = new RegExp(`${field}=(-|[0-9.]+)`).exec(line);
  assert.ok(match !== null, `${field} missing from ${line}`);
  return match[1] === '-' ? null : Number(match[1]);
}

describe('relay', () => {
  let ctx;
  const extras = [];
  const devices = [];

  beforeEach(async () => {
    ctx = await startRelay();
  });

  afterEach(async () => {
    for (const ws of devices) ws.terminate();
    devices.length = 0;
    for (const extra of extras) await extra.stop();
    extras.length = 0;
    if (ctx) await ctx.stop();
    ctx = null;
  });

  async function device(wsUrl = ctx.wsUrl, options = {}) {
    const ws = await connectDevice(wsUrl, TEST_TOKEN, options);
    devices.push(ws);
    return ws;
  }

  async function extraRelay(overrides, logger) {
    const extra = await startRelay(overrides, logger);
    extras.push(extra);
    return extra;
  }

  it('health reports device state', async () => {
    let { res, text } = await fetchText(`${ctx.baseUrl}/relay/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(text), { status: 'ok', deviceConnected: false });

    await device();

    ({ res, text } = await fetchText(`${ctx.baseUrl}/relay/health`));
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(text), { status: 'ok', deviceConnected: true });
  });

  it('upgrade without bearer is refused with 401', async () => {
    assert.equal(await rejectedUpgradeStatus(ctx.wsUrl, {}), 401);
  });

  it('upgrade with wrong bearer is refused with 401', async () => {
    assert.equal(await rejectedUpgradeStatus(ctx.wsUrl, { authorization: 'Bearer nope' }), 401);
  });

  it('upgrade on another path is refused with 404', async () => {
    const url = `${ctx.baseUrl.replace('http://', 'ws://')}/other`;
    assert.equal(await rejectedUpgradeStatus(url, { authorization: `Bearer ${TEST_TOKEN}` }), 404);
  });

  it('malformed upgrade target is refused with 400', async () => {
    const raw = await rawRequest(
      ctx.port,
      'GET http://[/x HTTP/1.1\r\nHost: relay.invalid\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n' +
        'Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n',
    );

    assert.match(raw, /^HTTP\/1\.1 400 /);
    const { res } = await fetchText(`${ctx.baseUrl}/relay/health`);
    assert.equal(res.status, 200);
  });

  it('malformed request target returns 400', async () => {
    const raw = await rawRequest(
      ctx.port,
      'GET http://[/x HTTP/1.1\r\nHost: relay.invalid\r\nConnection: close\r\n\r\n',
    );

    assert.match(raw, /^HTTP\/1\.1 400 /);
    assert.match(raw, /invalid request target/);
  });

  it('request without device returns 503', async () => {
    const { res, text } = await fetchText(`${ctx.baseUrl}/mcp`, { method: 'POST', body: '{}' });

    assert.equal(res.status, 503);
    assert.equal(JSON.parse(text).error, 'device not connected');
  });

  it('request is forwarded as a frame and the response frame is returned', async () => {
    const ws = await device();
    const sent = JSON.stringify({ hello: 'world' });
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp?x=1`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-test': '1' },
      body: sent,
    });

    const frame = await framePromise;
    assert.equal(frame.type, 'request');
    assert.match(frame.id, UUID_PATTERN);
    assert.equal(frame.method, 'POST');
    assert.equal(frame.path, '/mcp?x=1');
    assert.deepEqual(frame.headers['x-test'], ['1']);
    assert.deepEqual(frame.headers['content-type'], ['application/json']);
    assert.equal(frame.headers.host, undefined);
    assert.equal(frame.headers['content-length'], undefined);
    assert.equal(Buffer.from(frame.body, 'base64').toString('utf8'), sent);

    const body = '{"ok":true}';
    respond(ws, frame.id, {
      status: 201,
      headers: {
        'Content-Type': ['application/json'],
        'Mcp-Session-Id': ['abc'],
        'Transfer-Encoding': ['chunked'],
      },
      body,
    });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 201);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.equal(res.headers.get('mcp-session-id'), 'abc');
    assert.equal(res.headers.get('transfer-encoding'), null);
    assert.equal(text, body);
  });

  it('absolute-form request target is forwarded as origin-form', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const rawPromise = rawRequest(
      ctx.port,
      'GET http://relay.invalid/mcp?x=1 HTTP/1.1\r\nHost: relay.invalid\r\nConnection: close\r\n\r\n',
    );

    const frame = await framePromise;
    assert.equal(frame.path, '/mcp?x=1');
    respond(ws, frame.id, { body: 'ok' });

    const raw = await rawPromise;
    assert.match(raw, /^HTTP\/1\.1 200 /);
  });

  it('trusted proxy headers are preserved', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`, {
      headers: {
        'x-forwarded-host': 'public.example',
        'x-forwarded-proto': 'https',
        'x-forwarded-for': '203.0.113.9',
      },
    });

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], ['public.example']);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['https']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['203.0.113.9', '127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('untrusted proxy headers are overwritten', async () => {
    const untrusted = await extraRelay({ trustProxy: false });
    const ws = await device(untrusted.wsUrl);
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${untrusted.baseUrl}/mcp`, {
      headers: {
        'x-forwarded-host': 'public.example',
        'x-forwarded-proto': 'https',
        'x-forwarded-for': '203.0.113.9',
      },
    });

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], [`127.0.0.1:${untrusted.port}`]);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['http']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('missing proxy headers are filled in', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], [`127.0.0.1:${ctx.port}`]);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['http']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('head and 204 responses carry no body', async () => {
    const ws = await device();

    let framePromise = nextFrame(ws);
    let fetchPromise = fetchText(`${ctx.baseUrl}/x`, { method: 'HEAD' });
    let frame = await framePromise;
    assert.equal(frame.method, 'HEAD');
    respond(ws, frame.id, { body: 'ignored' });
    let { res, text } = await fetchPromise;
    assert.equal(res.status, 200);
    assert.equal(text, '');
    assert.equal(res.headers.get('content-length'), null);

    framePromise = nextFrame(ws);
    fetchPromise = fetchText(`${ctx.baseUrl}/x`, { method: 'POST', body: 'y' });
    frame = await framePromise;
    respond(ws, frame.id, { status: 204, body: 'ignored' });
    ({ res, text } = await fetchPromise);
    assert.equal(res.status, 204);
    assert.equal(text, '');
  });

  it('device not answering returns 504', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);

    const { res, text } = await fetchText(`${ctx.baseUrl}/mcp`, { method: 'POST', body: '{}' });

    await framePromise;
    assert.equal(res.status, 504);
    assert.equal(JSON.parse(text).error, 'device did not answer in time');
  });

  it('body over the limit returns 413 and closes the connection', async () => {
    const ws = await device();
    const chunk = 'a'.repeat(2048);

    const raw = await rawRequest(
      ctx.port,
      `POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nTransfer-Encoding: chunked\r\n\r\n${chunk.length.toString(16)}\r\n${chunk}\r\n0\r\n\r\n`,
    );

    assert.match(raw, /^HTTP\/1\.1 413 /);
    assert.match(raw, /\r\nconnection: close\r\n/i);
    assert.equal(await frameOrNothing(ws, 200), NO_FRAME);
  });

  it('declared content-length over the limit returns 413 before reading', async () => {
    const ws = await device();

    const raw = await rawRequest(
      ctx.port,
      'POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n',
    );

    assert.match(raw, /^HTTP\/1\.1 413 /);
    assert.equal(await frameOrNothing(ws, 200), NO_FRAME);
  });

  it('oversized device response returns 502', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);

    const frame = await framePromise;
    respond(ws, frame.id, { body: 'a'.repeat(2049) });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 502);
    assert.equal(JSON.parse(text).error, 'device response too large');
  });

  it('pending limit returns 503', async () => {
    const ws = await device();
    const firstFrame = nextFrame(ws);
    const first = fetchText(`${ctx.baseUrl}/one`);
    const frame1 = await firstFrame;
    const secondFrame = nextFrame(ws);
    const second = fetchText(`${ctx.baseUrl}/two`);
    const frame2 = await secondFrame;

    const { res, text } = await fetchText(`${ctx.baseUrl}/three`);
    assert.equal(res.status, 503);
    assert.equal(JSON.parse(text).error, 'too many pending requests');

    respond(ws, frame1.id, { status: 200 });
    respond(ws, frame2.id, { status: 201 });
    assert.equal((await first).res.status, 200);
    assert.equal((await second).res.status, 201);
  });

  it('pending limit counts uploads in progress and frees aborted ones', async () => {
    // Body and answer timeouts far beyond every deadline below: only the aborts free the upload slots, and the
    // held request keeps its slot until the test answers it.
    const patient = await extraRelay({ bodyTimeoutMs: 60_000, requestTimeoutMs: 60_000 });
    const ws = await device(patient.wsUrl);
    const dispatched = requestsDispatched(patient.relay.server, 2);
    const uploads = await Promise.all([
      openSocket(patient.port, STALLED_UPLOAD),
      openSocket(patient.port, STALLED_UPLOAD),
    ]);
    try {
      await withDeadline(dispatched, DEADLINE_MS, 'both uploads to be admitted');
      const { res, text } = await withDeadline(
        fetchText(`${patient.baseUrl}/three`),
        DEADLINE_MS,
        'the third request to be refused',
      );
      assert.equal(res.status, 503);
      assert.equal(JSON.parse(text).error, 'too many pending requests');
    } finally {
      for (const socket of uploads) socket.destroy();
    }

    // The device keeps '/held' unanswered, so '/after' needs the other slot: both aborts must free theirs.
    const held = new Promise((resolve) => {
      ws.on('message', (data) => {
        const frame = JSON.parse(data.toString('utf8'));
        if (frame.type !== 'request') return;
        if (frame.path === '/held') resolve(frame);
        else respond(ws, frame.id, { body: 'freed' });
      });
    });
    const answered = async (path) => (await fetchText(`${patient.baseUrl}${path}`)).text === 'freed';
    await eventually(() => answered('/probe'), DEADLINE_MS, 'an aborted upload to free its slot');
    const heldFetch = fetchText(`${patient.baseUrl}/held`);
    const heldFrame = await withDeadline(held, DEADLINE_MS, 'the held request to reach the device');
    await eventually(() => answered('/after'), DEADLINE_MS, 'the other aborted upload to free its slot');
    respond(ws, heldFrame.id, { body: 'held' });
    assert.equal((await heldFetch).text, 'held');
  });

  it('stalled upload gets 408 after the body timeout and frees its slot', async () => {
    const strict = await extraRelay({ maxPending: 1, bodyTimeoutMs: 150 });
    const ws = await device(strict.wsUrl);

    const raw = await rawRequest(strict.port, STALLED_UPLOAD);

    assert.match(raw, /^HTTP\/1\.1 408 /);
    assert.match(raw, /\r\nconnection: close\r\n/i);
    assert.match(raw, /request body not received within 150 ms/);
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${strict.baseUrl}/after`);
    const frame = await withDeadline(framePromise, DEADLINE_MS, 'the next request to reach the device');
    assert.equal(frame.path, '/after');
    respond(ws, frame.id, { body: 'freed' });
    assert.equal((await fetchPromise).res.status, 200);
  });

  it('upload trickling its body still gets 408 at the body deadline', async () => {
    const strict = await extraRelay({ bodyTimeoutMs: 150 });
    await device(strict.wsUrl);
    const socket = await openSocket(strict.port, UPLOAD_HEADERS);
    // 100 bytes at one per 30 ms take 3 s: a deadline that each byte pushed back would miss DEADLINE_MS.
    const trickle = setInterval(() => socket.write('a'), 30);
    socket.once('data', () => clearInterval(trickle));
    try {
      assert.match(await replyOf(socket), /^HTTP\/1\.1 408 /);
    } finally {
      clearInterval(trickle);
    }
  });

  it('malformed and unknown device frames are ignored', async () => {
    const ws = await device();
    ws.send('nope');
    ws.send('{"type":"hello"}');
    ws.send('{"type":"response","id":"unknown","status":200}');
    ws.send('{"type":"response","id":"x","status":150}');

    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);
    const frame = await framePromise;
    respond(ws, frame.id, { body: 'still alive' });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 200);
    assert.equal(text, 'still alive');
  });

  it('new device replaces the previous one', async () => {
    const first = await device();
    const firstClosed = once(first, 'close');
    const firstFrame = nextFrame(first);
    const pendingFetch = fetchText(`${ctx.baseUrl}/mcp`);
    await firstFrame;

    const second = await device();

    const [code] = await firstClosed;
    assert.equal(code, CLOSE_REPLACED);
    assert.equal((await pendingFetch).res.status, 502);

    const secondFrame = nextFrame(second);
    const nextFetch = fetchText(`${ctx.baseUrl}/mcp`);
    const frame = await secondFrame;
    respond(second, frame.id, { body: 'from second' });
    const { res, text } = await nextFetch;
    assert.equal(res.status, 200);
    assert.equal(text, 'from second');
  });

  it('device disconnect fails in-flight requests with 502', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);
    await framePromise;

    ws.close();

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 502);
    assert.equal(JSON.parse(text).error, 'device disconnected');
  });

  it('relay pings the device', async () => {
    const pinging = await extraRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 5_000 });
    const ws = await device(pinging.wsUrl);

    const frame = await Promise.race([nextFrame(ws, 'ping'), sleep(500, NO_FRAME)]);

    assert.deepEqual(frame, { type: 'ping' });
  });

  it('unresponsive device is terminated', async () => {
    const strict = await extraRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 120 });
    const ws = await device(strict.wsUrl, { autoPong: false });

    const outcome = await Promise.race([once(ws, 'close').then(() => 'closed'), sleep(1_000, 'still open')]);

    assert.equal(outcome, 'closed');
    const { text } = await fetchText(`${strict.baseUrl}/relay/health`);
    assert.equal(JSON.parse(text).deviceConnected, false);
  });

  it('shutdown closes device socket with 1001', async () => {
    const ws = await device();
    const closed = once(ws, 'close');

    await ctx.stop();
    ctx = null;

    const [code] = await closed;
    assert.equal(code, 1001);
  });

  describe('timing log', () => {
    const TOOL_CALL_BODY = JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/call',
      params: { name: 'tap_node', arguments: { text: 'secret-arg' } },
    });

    /** A relay with a recording logger plus a connected device. */
    async function recorded(overrides = {}) {
      const logger = recordingLogger();
      const extra = await extraRelay(overrides, logger);
      return { logger, extra };
    }

    async function oneLine(logger) {
      await eventually(() => timingLines(logger).length === 1, DEADLINE_MS, 'one timing line');
      return timingLines(logger)[0];
    }

    it('proxied request logs one timing line', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const fetchPromise = fetchText(`${extra.baseUrl}/mcp`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: TOOL_CALL_BODY,
      });

      const frame = await framePromise;
      respond(ws, frame.id, { body: 'answer' });
      const { res } = await fetchPromise;
      assert.equal(res.status, 200);

      const line = await oneLine(logger);
      assert.match(line, new RegExp(`rid=${frame.id.slice(0, 8)}`));
      assert.match(line, /method=POST/);
      assert.match(line, / path=\/mcp /);
      assert.match(line, /rpc=tools\/call/);
      assert.match(line, /tool=tap_node/);
      assert.match(line, /status=200/);
      assert.match(line, new RegExp(`reqBytes=${Buffer.byteLength(TOOL_CALL_BODY)}`));
      assert.match(line, /resBytes=6/);
      assert.match(line, /inflight=0/);
      for (const field of ['idleMs', 'sentAt', 'bodyMs', 'deviceMs', 'totalMs']) {
        assert.equal(typeof numberOf(line, field), 'number', `${field} should be numeric in ${line}`);
      }
    });

    it('timing line keeps secrets out', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const fetchPromise = fetchText(`${extra.baseUrl}/mcp?code=secret-code`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: 'Bearer secret-client-token' },
        body: TOOL_CALL_BODY,
      });

      const frame = await framePromise;
      respond(ws, frame.id, { body: 'answer' });
      await fetchPromise;

      const line = await oneLine(logger);
      assert.equal(logger.lines.some((entry) => entry.includes('secret')), false);
      assert.equal(line.includes('127.0.0.1'), false);
    });

    it('capability path is redacted', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const fetchPromise = fetchText(`${extra.baseUrl}/s/secret-token`);

      const frame = await framePromise;
      respond(ws, frame.id, { body: 'file' });
      await fetchPromise;

      const line = await oneLine(logger);
      assert.match(line, / path=\/s\/<redacted> /);
      assert.match(line, /rpc=- /);
      assert.equal(line.includes('secret-token'), false);
    });

    it('deviceMs covers the device answer time', async () => {
      const { logger, extra } = await recorded({ requestTimeoutMs: 2_000 });
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const fetchPromise = fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });

      const frame = await framePromise;
      await sleep(60);
      respond(ws, frame.id, { body: 'answer' });
      await fetchPromise;

      const line = await oneLine(logger);
      assert.ok(numberOf(line, 'deviceMs') >= 50, line);
    });

    it('request without device is logged with 503', async () => {
      const { logger, extra } = await recorded();

      const { res } = await fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      assert.equal(res.status, 503);

      const line = await oneLine(logger);
      assert.match(line, /status=503/);
      assert.match(line, /rid=- /);
      assert.equal(numberOf(line, 'bodyMs'), null);
      assert.equal(numberOf(line, 'deviceMs'), null);
    });

    it('device timeout is logged with 504', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);

      const { res } = await fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      await framePromise;
      assert.equal(res.status, 504);

      const line = await oneLine(logger);
      assert.match(line, /status=504/);
      assert.equal(typeof numberOf(line, 'deviceMs'), 'number');
    });

    it('health checks are not logged', async () => {
      const { logger, extra } = await recorded();

      const { res } = await fetchText(`${extra.baseUrl}/relay/health`);
      assert.equal(res.status, 200);
      await sleep(50);

      assert.deepEqual(timingLines(logger), []);
    });

    it('malformed target is logged with 400', async () => {
      const { logger, extra } = await recorded();

      const raw = await rawRequest(
        extra.port,
        'GET http://[/x HTTP/1.1\r\nHost: relay.invalid\r\nConnection: close\r\n\r\n',
      );
      assert.match(raw, /^HTTP\/1\.1 400 /);

      const line = await oneLine(logger);
      assert.match(line, /status=400/);
      assert.match(line, / path=- /);
    });

    it('client abort is flagged', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const socket = await openSocket(
        extra.port,
        'POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nContent-Length: 2\r\n\r\n{}',
      );

      const frame = await framePromise;
      socket.destroy();

      const line = await oneLine(logger);
      assert.match(line, /aborted=1$/);
      assert.match(line, /status=- /);
      assert.equal(numberOf(line, 'deviceMs'), null);

      respond(ws, frame.id, { body: 'late' });
      await sleep(50);
      assert.equal(timingLines(logger).length, 1);
    });

    it('oversized body is logged with 413', async () => {
      const { logger, extra } = await recorded();
      await device(extra.wsUrl);
      const chunk = 'a'.repeat(2048);

      const raw = await rawRequest(
        extra.port,
        `POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nTransfer-Encoding: chunked\r\n\r\n${chunk.length.toString(16)}\r\n${chunk}\r\n0\r\n\r\n`,
      );
      assert.match(raw, /^HTTP\/1\.1 413 /);

      const line = await oneLine(logger);
      assert.match(line, /status=413/);
      assert.match(line, /rid=- /);
      assert.equal(typeof numberOf(line, 'bodyMs'), 'number');
      assert.equal(numberOf(line, 'deviceMs'), null);
    });

    it('stalled upload is logged with 408', async () => {
      const logger = recordingLogger();
      const strict = await extraRelay({ maxPending: 1, bodyTimeoutMs: 150 }, logger);
      await device(strict.wsUrl);

      const raw = await rawRequest(strict.port, STALLED_UPLOAD);
      assert.match(raw, /^HTTP\/1\.1 408 /);

      const line = await oneLine(logger);
      assert.match(line, /status=408/);
      assert.match(line, /rid=- /);
      assert.ok(numberOf(line, 'bodyMs') >= 100, line);
      assert.equal(numberOf(line, 'deviceMs'), null);
    });

    it('oversized device response is logged with 502', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);
      const framePromise = nextFrame(ws);
      const fetchPromise = fetchText(`${extra.baseUrl}/mcp`);

      const frame = await framePromise;
      respond(ws, frame.id, { body: 'a'.repeat(2049) });
      const { res, text } = await fetchPromise;
      assert.equal(res.status, 502);

      const line = await oneLine(logger);
      assert.match(line, /status=502/);
      assert.match(line, new RegExp(`rid=${frame.id.slice(0, 8)}`));
      assert.equal(typeof numberOf(line, 'deviceMs'), 'number');
      assert.match(line, new RegExp(`resBytes=${Buffer.byteLength(text)}`));
    });

    it('bodiless answers log resBytes=0', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);

      let framePromise = nextFrame(ws);
      let fetchPromise = fetchText(`${extra.baseUrl}/x`, { method: 'HEAD' });
      let frame = await framePromise;
      respond(ws, frame.id, { body: 'ignored' });
      await fetchPromise;

      framePromise = nextFrame(ws);
      fetchPromise = fetchText(`${extra.baseUrl}/x`, { method: 'POST', body: 'y' });
      frame = await framePromise;
      respond(ws, frame.id, { status: 204, body: 'ignored' });
      await fetchPromise;

      await eventually(() => timingLines(logger).length === 2, DEADLINE_MS, 'two timing lines');
      const [head, noContent] = timingLines(logger);
      assert.match(head, /status=200/);
      assert.match(head, /resBytes=0/);
      assert.match(noContent, /status=204/);
      assert.match(noContent, /resBytes=0/);
    });

    it('inflight counts requests already in flight', async () => {
      const { logger, extra } = await recorded();
      const ws = await device(extra.wsUrl);

      const firstFrame = nextFrame(ws);
      const first = fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      const frame1 = await firstFrame;
      const secondFrame = nextFrame(ws);
      const second = fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      const frame2 = await secondFrame;

      respond(ws, frame1.id, { body: 'a' });
      respond(ws, frame2.id, { body: 'b' });
      await first;
      await second;

      await eventually(() => timingLines(logger).length === 2, DEADLINE_MS, 'two timing lines');
      const lines = timingLines(logger);
      const lineOf = (id) => lines.find((line) => line.includes(`rid=${id.slice(0, 8)}`));
      assert.match(lineOf(frame1.id), /inflight=0/);
      assert.match(lineOf(frame2.id), /inflight=1/);
    });

    it('timing log failure is contained', async () => {
      const lines = [];
      const push = (message) => {
        lines.push(message);
      };
      const exploding = {
        lines,
        info(message) {
          if (message.startsWith('relay: request ')) throw new Error('boom');
          lines.push(message);
        },
        warn: push,
        error: push,
      };
      const extra = await extraRelay({}, exploding);

      const first = await fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      const second = await fetchText(`${extra.baseUrl}/mcp`, { method: 'POST', body: '{}' });
      assert.equal(first.res.status, 503);
      assert.equal(second.res.status, 503);

      await eventually(
        () => lines.filter((line) => line === 'relay: timing log failed').length === 2,
        DEADLINE_MS,
        'two contained failures',
      );
    });
  });
});
