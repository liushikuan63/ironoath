import { test } from 'node:test';
import assert from 'node:assert/strict';
import { redactEvidence } from './runtime-evidence-redaction.mjs';

test('握手错误URL和失败栈保留诊断信息并去掉查询票据', () => {
  const secret = 'synthetic.ticket_123-456';
  const result = redactEvidence({ consoleErrors: [`WebSocket connection to 'ws://localhost:9835/ws?token=${secret}&channel=WORLD' failed`],
    failure: { stack: `Error: wss://test/ws?AUTH_TOKEN=${secret}\n at socket:1` } });
  assert.equal(JSON.stringify(result).includes(secret), false);
  assert.match(result.consoleErrors[0], /channel=WORLD/);
  assert.match(result.failure.stack, /at socket:1/);
});
test('结构化字段、嵌套数组及Bearer票据脱敏，业务数据不改', () => {
  const result = redactEvidence({ data: { authToken: 'synthetic-auth', messages: [{ content: '同一句话', count: 3 }] },
    errors: ['Authorization: Bearer synthetic.ticket'] });
  assert.equal(result.data.authToken, '[REDACTED]');
  assert.equal(result.errors[0], 'Authorization: Bearer [REDACTED]');
  assert.deepEqual(result.data.messages, [{ content: '同一句话', count: 3 }]);
});
