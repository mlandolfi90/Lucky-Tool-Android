/**
 * Log-safe pieces of the per-request timing line. Pure: these functions never receive the request object or its
 * headers. `rpcSummary` reads only the top-level `method` and `params.name` of the body, regex-filtered, so no
 * argument, param or id reaches the line.
 */

export const RPC_SNIFF_MAX_BYTES = 64 * 1024;
/** Characters of the frame id logged as `rid`; the phone logs the same prefix (a full UUID is redacted there). */
export const RID_LENGTH = 8;

const MAX_PATH_CHARS = 128;
const CAPABILITY_PREFIX = '/s/';
const RPC_METHOD = /^[A-Za-z][A-Za-z0-9_./-]{0,63}$/;
const TOOL_NAME = /^[A-Za-z0-9_.-]{1,128}$/;
const NOT_INSPECTED = Object.freeze({ rpc: '-', tool: null });
const UNREADABLE = Object.freeze({ rpc: '?', tool: null });

export function shortId(id) {
  return id.slice(0, RID_LENGTH);
}

/** `pathname` is WHATWG-parsed (no query, percent-encoded); capability links are redacted like on the phone. */
export function safePath(pathname) {
  if (pathname.startsWith(CAPABILITY_PREFIX)) return `${CAPABILITY_PREFIX}<redacted>`;
  return pathname.length > MAX_PATH_CHARS ? `${pathname.slice(0, MAX_PATH_CHARS)}…` : pathname;
}

/**
 * JSON-RPC method (and tool name for `tools/call`) of a `POST /mcp` JSON body of at most RPC_SNIFF_MAX_BYTES.
 * Any other request (OAuth `/token` and `/register` bodies included) is never parsed: `rpc` is `-`.
 * Unparseable or unsafe values give `?`; a batch gives `batch`. Params, arguments and the JSON-RPC id are never read.
 */
export function rpcSummary(input) {
  if (input === null) return NOT_INSPECTED;
  const { method, pathname, contentType, body } = input;
  if (method !== 'POST' || pathname !== '/mcp') return NOT_INSPECTED;
  const mediaType = (contentType ?? '').split(';')[0].trim().toLowerCase();
  if (mediaType !== 'application/json' || body.length > RPC_SNIFF_MAX_BYTES) return NOT_INSPECTED;
  let message;
  try {
    message = JSON.parse(body.toString('utf8'));
  } catch {
    return UNREADABLE;
  }
  if (Array.isArray(message)) return { rpc: 'batch', tool: null };
  const rpc = typeof message?.method === 'string' && RPC_METHOD.test(message.method) ? message.method : '?';
  const name = message?.params?.name;
  const tool = rpc === 'tools/call' && typeof name === 'string' && TOOL_NAME.test(name) ? name : null;
  return { rpc, tool };
}

const ms = (value) => (value === null ? '-' : value.toFixed(1));

/** One log line; `null` fields are phases the request never reached and print as `-`. */
export function formatRequestLog({
  rid, method, path, rpc, tool, status, reqBytes, resBytes, inflight, idleMs, sentAt, bodyMs, deviceMs, totalMs,
  aborted,
}) {
  const parts = ['relay: request', `rid=${rid}`, `method=${method}`, `path=${path}`, `rpc=${rpc}`];
  if (tool !== null) parts.push(`tool=${tool}`);
  parts.push(
    `status=${status ?? '-'}`,
    `reqBytes=${reqBytes}`,
    `resBytes=${resBytes}`,
    `inflight=${inflight}`,
    `idleMs=${idleMs ?? '-'}`,
    `sentAt=${sentAt ?? '-'}`,
    `bodyMs=${ms(bodyMs)}`,
    `deviceMs=${ms(deviceMs)}`,
    `totalMs=${ms(totalMs)}`,
  );
  if (aborted) parts.push('aborted=1');
  return parts.join(' ');
}
