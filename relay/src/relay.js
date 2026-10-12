import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { WebSocketServer } from 'ws';
import { FRAME_TYPES, decodeDeviceFrame, encodePing, encodeRequestFrame } from './frames.js';
import { formatRequestLog, rpcSummary, safePath, shortId } from './requestLog.js';

export const DEVICE_PATH = '/device';
export const HEALTH_PATH = '/relay/health';
export const CLOSE_REPLACED = 4000;

const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'expect',
  'host',
  'content-length',
]);
const NO_BODY_STATUSES = new Set([204, 304]);
/** Answered before the request body was fully read: the connection cannot be reused. */
const CLOSE_CONNECTION_STATUSES = new Set([408, 413]);
const FRAME_OVERHEAD_BYTES = 64 * 1024;
const CLOSE_GOING_AWAY = 1001;

/** WHATWG parse of a request target; `null` when Node's parser accepted something `URL` rejects. */
function parseTarget(target) {
  try {
    return new URL(target, 'http://relay.invalid');
  } catch {
    return null;
  }
}

export class RelayError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'RelayError';
    this.status = status;
  }
}

/**
 * Builds the relay: an HTTP server that proxies every request (except HEALTH_PATH) to the single connected
 * device over its WebSocket, plus the bearer-gated DEVICE_PATH upgrade endpoint.
 * Returns `{ server, close, isDeviceConnected }`; the caller decides where `server` listens.
 */
export function createRelay(config, logger = console) {
  const pending = new Map();
  let device = null;
  /**
   * Requests between admission and answer, uploads still being read included (bounded by maxPending).
   * An upload that stalls is answered 408 after bodyTimeoutMs, which frees its slot.
   */
  let inFlight = 0;

  const largestBody = Math.max(config.maxBodyBytes, config.maxResponseBytes);
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: Math.ceil((largestBody * 4) / 3) + FRAME_OVERHEAD_BYTES,
  });
  const server = createServer((req, res) => {
    handleHttp(req, res).catch((err) => {
      logger.error(`relay: unhandled error: ${err?.stack ?? err}`);
      if (!res.headersSent) sendJson(res, 500, { error: 'internal relay error' });
    });
  });
  server.on('upgrade', handleUpgrade);

  function isAuthorized(req) {
    const given = Buffer.from(req.headers.authorization ?? '', 'utf8');
    const expected = Buffer.from(`Bearer ${config.deviceToken}`, 'utf8');
    return given.length === expected.length && timingSafeEqual(given, expected);
  }

  function handleUpgrade(req, socket, head) {
    const url = parseTarget(req.url);
    if (url === null) {
      rejectUpgrade(socket, 400, 'Bad Request');
      return;
    }
    if (url.pathname !== DEVICE_PATH) {
      rejectUpgrade(socket, 404, 'Not Found');
      return;
    }
    if (!isAuthorized(req)) {
      logger.warn(`relay: rejected device handshake from ${req.socket.remoteAddress}`);
      rejectUpgrade(socket, 401, 'Unauthorized');
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => attachDevice(ws, req));
  }

  function rejectUpgrade(socket, status, text) {
    socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    socket.destroy();
  }

  function attachDevice(ws, req) {
    if (device !== null) {
      logger.info('relay: new device connection replaces the previous one');
      const previous = device;
      detachDevice(previous, 'device connection replaced');
      previous.socket.close(CLOSE_REPLACED, 'replaced by a new device connection');
    }
    const entry = { socket: ws, lastSeen: Date.now(), heartbeat: null };
    entry.heartbeat = setInterval(() => heartbeat(entry), config.heartbeatIntervalMs);
    device = entry;
    logger.info(`relay: device connected from ${req.socket.remoteAddress}`);
    ws.on('message', (data) => {
      entry.lastSeen = Date.now();
      handleDeviceMessage(data);
    });
    ws.on('pong', () => {
      entry.lastSeen = Date.now();
    });
    ws.on('close', (code) => {
      logger.info(`relay: device disconnected (code ${code})`);
      detachDevice(entry, 'device disconnected');
    });
    ws.on('error', (err) => {
      logger.warn(`relay: device socket error: ${err.message}`);
    });
  }

  function detachDevice(entry, reason) {
    clearInterval(entry.heartbeat);
    if (device === entry) {
      device = null;
      failAllPending(reason);
    }
  }

  function heartbeat(entry) {
    if (Date.now() - entry.lastSeen > config.heartbeatTimeoutMs) {
      logger.warn('relay: device unresponsive, terminating its socket');
      entry.socket.terminate();
      return;
    }
    if (entry.socket.readyState === entry.socket.OPEN) {
      entry.socket.send(encodePing());
    }
  }

  function handleDeviceMessage(data) {
    const frame = decodeDeviceFrame(data.toString('utf8'));
    if (frame === null) {
      logger.warn('relay: ignoring malformed device frame');
      return;
    }
    if (frame.type === FRAME_TYPES.PONG) return;
    if (frame.type !== FRAME_TYPES.RESPONSE) {
      logger.warn(`relay: ignoring device frame of type "${frame.type}"`);
      return;
    }
    const waiter = pending.get(frame.id);
    if (waiter === undefined) {
      logger.warn(`relay: response for unknown or expired request ${frame.id}`);
      return;
    }
    pending.delete(frame.id);
    clearTimeout(waiter.timer);
    waiter.resolve(frame);
  }

  function failAllPending(reason) {
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(new RelayError(502, reason));
    }
    pending.clear();
  }

  /**
   * Starts the timing record of one request and logs it once, when its response closes: answered, refused, failed
   * (the createServer 500 included, with resBytes=0) or abandoned by the client (`aborted=1`; phases still running
   * print `-`). `totalMs`, status and abort state are read first; JSON-RPC sniffing runs after them, so it never
   * adds to the measured latency. A failure while building or writing the line is contained here: a missing log
   * line must never crash the relay.
   */
  function startTiming(req, res, url) {
    const timing = {
      rid: '-',
      method: req.method,
      path: url === null ? '-' : safePath(url.pathname),
      reqBytes: 0,
      resBytes: 0,
      inflight: inFlight,
      idleMs: null,
      sentAt: null,
      bodyMs: null,
      deviceMs: null,
      rpcInput: null,
    };
    const startedAt = performance.now();
    res.once('close', () => {
      const totalMs = performance.now() - startedAt;
      const status = res.headersSent ? res.statusCode : null;
      const aborted = !res.writableFinished;
      try {
        const { rpcInput, ...fields } = timing;
        logger.info(formatRequestLog({ ...fields, ...rpcSummary(rpcInput), status, totalMs, aborted }));
      } catch {
        logger.warn('relay: timing log failed');
      }
    });
    return timing;
  }

  async function handleHttp(req, res) {
    const url = parseTarget(req.url);
    if (url !== null && url.pathname === HEALTH_PATH) {
      sendJson(res, 200, { status: 'ok', deviceConnected: device !== null });
      return;
    }
    const timing = startTiming(req, res, url);
    if (url === null) {
      timing.resBytes = sendJson(res, 400, { error: 'invalid request target' }, true);
      return;
    }
    try {
      if (device === null) throw new RelayError(503, 'device not connected');
      if (inFlight >= config.maxPending) throw new RelayError(503, 'too many pending requests');
      inFlight += 1;
      try {
        const bodyStart = performance.now();
        let body;
        try {
          body = await readBody(req, config.maxBodyBytes, config.bodyTimeoutMs);
        } finally {
          timing.bodyMs = performance.now() - bodyStart;
        }
        timing.reqBytes = body.length;
        timing.rpcInput = {
          method: req.method,
          pathname: url.pathname,
          contentType: req.headers['content-type'],
          body,
        };
        const id = randomUUID();
        timing.rid = shortId(id);
        timing.sentAt = Date.now();
        timing.idleMs = device === null ? null : timing.sentAt - device.lastSeen;
        const deviceStart = performance.now();
        let response;
        try {
          response = await forward(req, url, body, id);
        } finally {
          timing.deviceMs = performance.now() - deviceStart;
        }
        if (response.body.length > config.maxResponseBytes) throw new RelayError(502, 'device response too large');
        timing.resBytes = writeResponse(req, res, response);
      } finally {
        inFlight -= 1;
      }
    } catch (err) {
      if (err instanceof RelayError) {
        timing.resBytes = sendJson(res, err.status, { error: err.message }, CLOSE_CONNECTION_STATUSES.has(err.status));
        return;
      }
      throw err;
    }
  }

  function forward(req, url, body, id) {
    if (device === null) {
      return Promise.reject(new RelayError(503, 'device not connected'));
    }
    const frame = encodeRequestFrame({
      id,
      method: req.method,
      path: `${url.pathname}${url.search}`,
      headers: forwardedHeaders(req),
      body,
    });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new RelayError(504, 'device did not answer in time'));
      }, config.requestTimeoutMs);
      pending.set(id, { resolve, reject, timer });
      device.socket.send(frame, (err) => {
        if (err && pending.delete(id)) {
          clearTimeout(timer);
          reject(new RelayError(502, `failed to send to device: ${err.message}`));
        }
      });
    });
  }

  function forwardedHeaders(req) {
    const headers = {};
    for (const [name, values] of Object.entries(req.headersDistinct)) {
      if (!HOP_BY_HOP_HEADERS.has(name)) headers[name] = values;
    }
    const observedHost = [req.headers.host ?? ''];
    const observedProto = [req.socket.encrypted ? 'https' : 'http'];
    if (config.trustProxy) {
      headers['x-forwarded-host'] ??= observedHost;
      headers['x-forwarded-proto'] ??= observedProto;
    } else {
      headers['x-forwarded-host'] = observedHost;
      headers['x-forwarded-proto'] = observedProto;
      delete headers['x-forwarded-for'];
    }
    headers['x-forwarded-for'] = [...(headers['x-forwarded-for'] ?? []), req.socket.remoteAddress ?? ''];
    return headers;
  }

  /** Returns the body bytes sent (0 for HEAD, 204 and 304). */
  function writeResponse(req, res, frame) {
    for (const [name, values] of Object.entries(frame.headers)) {
      if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) res.setHeader(name, values);
    }
    res.statusCode = frame.status;
    if (req.method === 'HEAD' || NO_BODY_STATUSES.has(frame.status)) {
      res.end();
      return 0;
    }
    res.setHeader('content-length', frame.body.length);
    res.end(frame.body);
    return frame.body.length;
  }

  function close() {
    return new Promise((resolve) => {
      if (device !== null) {
        const current = device;
        detachDevice(current, 'relay shutting down');
        current.socket.close(CLOSE_GOING_AWAY, 'relay shutting down');
      }
      wss.close();
      server.close(() => resolve());
      server.closeAllConnections();
    });
  }

  return {
    server,
    close,
    isDeviceConnected: () => device !== null,
  };
}

/**
 * Buffers the request body: 413 above `maxBytes`, 408 when it has not fully arrived within `timeoutMs`
 * (a stalled upload would otherwise hold its MAX_PENDING slot until the socket dies).
 */
function readBody(req, maxBytes, timeoutMs) {
  const declared = Number.parseInt(req.headers['content-length'] ?? '', 10);
  if (Number.isFinite(declared) && declared > maxBytes) {
    req.resume();
    return Promise.reject(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    };
    const timer = setTimeout(() => {
      fail(new RelayError(408, `request body not received within ${timeoutMs} ms`));
    }, timeoutMs);
    req.on('data', (chunk) => {
      if (settled) return;
      total += chunk.length;
      if (total > maxBytes) {
        fail(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(Buffer.concat(chunks));
    });
    // Also fires when the client disconnects mid-upload (Node destroys the request with 'aborted'),
    // which is what frees the in-flight slot.
    req.on('error', (err) => fail(new RelayError(400, `failed to read request body: ${err.message}`)));
  });
}

/** Returns the body bytes sent. */
function sendJson(res, status, payload, closeConnection = false) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const headers = { 'content-type': 'application/json', 'content-length': body.length };
  if (closeConnection) headers.connection = 'close';
  res.writeHead(status, headers);
  res.end(body);
  return body.length;
}
