import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { requestMidasPayment } from '../assets/scripts/net/MidasPayment';
import type { PayParams } from '../assets/scripts/net/generated/PayProtocol';

const params: PayParams = {
  mode: 'game',
  offerId: 'offer-test',
  buyQuantity: '600',
  env: '1',
  currencyType: 'CNY',
  signature: null,
  outTradeNo: '0123456789abcdef0123456789abcdef',
};

function installWx(value: unknown): () => void {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'wx');
  Object.defineProperty(globalThis, 'wx', { configurable: true, writable: true, value });
  return () => {
    if (previous) Object.defineProperty(globalThis, 'wx', previous);
    else Reflect.deleteProperty(globalThis, 'wx');
  };
}

test('Midas bridge refuses to launch an order with no frozen channel order id', async () => {
  let calls = 0;
  const restore = installWx({ requestMidasPayment: (options: Record<string, unknown>) => {
    calls++;
    (options.success as (() => void) | undefined)?.();
  } });
  try {
    const outcome = await requestMidasPayment({ ...params, outTradeNo: null });
    assert.equal(outcome, 'unsupported');
    assert.equal(calls, 0);
  } finally {
    restore();
  }
});

test('Midas bridge forwards the same frozen outTradeNo used by server order recovery', async () => {
  let sent: Record<string, unknown> | undefined;
  const restore = installWx({
    requestMidasPayment: (options: Record<string, unknown>) => {
      sent = options;
      (options.success as (() => void) | undefined)?.();
    },
  });
  try {
    const outcome = await requestMidasPayment(params);
    assert.equal(outcome, 'ok');
    assert.equal(sent?.outTradeNo, params.outTradeNo);
  } finally {
    restore();
  }
});
