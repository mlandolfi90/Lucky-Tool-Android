import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { loadConfig } from '../src/config.js';

const TOKEN = '0123456789abcdef';

describe('loadConfig', () => {
  it('applies defaults', () => {
    const config = loadConfig({ RELAY_DEVICE_TOKEN: TOKEN });

    assert.deepEqual(config, {
      port: 8080,
      deviceToken: TOKEN,
      requestTimeoutMs: 120_000,
      bodyTimeoutMs: 30_000,
      maxBodyBytes: 16 * 1024 * 1024,
      maxResponseBytes: 8 * 1024 * 1024,
      maxPending: 32,
      heartbeatIntervalMs: 25_000,
      heartbeatTimeoutMs: 60_000,
      trustProxy: true,
    });
  });

  it('reads overrides', () => {
    const config = loadConfig({
      RELAY_DEVICE_TOKEN: TOKEN,
      PORT: '9000',
      REQUEST_TIMEOUT_MS: '1000',
      BODY_TIMEOUT_MS: '60',
      MAX_BODY_BYTES: '10',
      MAX_RESPONSE_BYTES: '20',
      MAX_PENDING: '3',
      HEARTBEAT_INTERVAL_MS: '40',
      HEARTBEAT_TIMEOUT_MS: '50',
      TRUST_PROXY: 'false',
    });

    assert.deepEqual(config, {
      port: 9000,
      deviceToken: TOKEN,
      requestTimeoutMs: 1000,
      bodyTimeoutMs: 60,
      maxBodyBytes: 10,
      maxResponseBytes: 20,
      maxPending: 3,
      heartbeatIntervalMs: 40,
      heartbeatTimeoutMs: 50,
      trustProxy: false,
    });
  });

  it('rejects missing token', () => {
    assert.throws(() => loadConfig({}), /RELAY_DEVICE_TOKEN/);
  });

  it('rejects short token', () => {
    assert.throws(() => loadConfig({ RELAY_DEVICE_TOKEN: '0123456789abcde' }), /RELAY_DEVICE_TOKEN/);
  });

  it('rejects non-integer values', () => {
    for (const port of ['abc', '0', '12.5']) {
      assert.throws(() => loadConfig({ RELAY_DEVICE_TOKEN: TOKEN, PORT: port }), /PORT/, `PORT=${port}`);
    }
  });

  it('rejects out-of-range port', () => {
    assert.throws(() => loadConfig({ RELAY_DEVICE_TOKEN: TOKEN, PORT: '70000' }), /PORT/);
  });

  it('accepts timers up to the Node timer limit and rejects longer ones', () => {
    const timers = {
      REQUEST_TIMEOUT_MS: 'requestTimeoutMs',
      BODY_TIMEOUT_MS: 'bodyTimeoutMs',
      HEARTBEAT_INTERVAL_MS: 'heartbeatIntervalMs',
    };
    for (const [name, key] of Object.entries(timers)) {
      assert.equal(loadConfig({ RELAY_DEVICE_TOKEN: TOKEN, [name]: '2147483647' })[key], 2_147_483_647, name);
      assert.throws(
        () => loadConfig({ RELAY_DEVICE_TOKEN: TOKEN, [name]: '2147483648' }),
        new RegExp(`${name} must be at most 2147483647`),
        name,
      );
    }
  });

  it('rejects invalid booleans', () => {
    assert.throws(() => loadConfig({ RELAY_DEVICE_TOKEN: TOKEN, TRUST_PROXY: 'maybe' }), /TRUST_PROXY/);
  });
});
