import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { requestMidasPayment } from '../assets/scripts/net/MidasPayment';
import type { PayParams } from '../assets/scripts/net/generated/PayProtocol';

const params: PayParams = {
  signData: null,
  paySig: null,
  signature: null,
  outTradeNo: '0123456789abcdef0123456789abcdef',
};
const signData = '{"mode":"goods","offerId":"offer-test","buyQuantity":2,"env":1,"currencyType":"CNY","platform":"android","productId":"first_charge","goodsPrice":300,"outTradeNo":"0123456789abcdef0123456789abcdef"}';
const paySig = 'a'.repeat(64);
const userSignature = 'b'.repeat(64);
const directItemParams = { ...params, signData, paySig, signature: userSignature } as unknown as PayParams;

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
  const restore = installWx({
    requestMidasPayment: () => { calls++; },
    requestMidasPaymentGameItem: () => { calls++; },
  });
  try {
    const outcome = await requestMidasPayment({ ...directItemParams, outTradeNo: null });
    assert.equal(outcome, 'unsupported');
    assert.equal(calls, 0);
  } finally {
    restore();
  }
});

test('Midas bridge forwards signed GameItem data and never uses the game-currency API', async () => {
  let legacyCalls = 0;
  let gameItemCalls = 0;
  let sent: Record<string, unknown> | undefined;
  const restore = installWx({
    requestMidasPayment: (options: Record<string, unknown>) => {
      legacyCalls++;
      (options.success as (() => void) | undefined)?.();
    },
    requestMidasPaymentGameItem: (options: Record<string, unknown>) => {
      gameItemCalls++;
      sent = options;
      (options.success as (() => void) | undefined)?.();
    },
  });
  try {
    const outcome = await requestMidasPayment(directItemParams);
    assert.equal(outcome, 'ok');
    assert.equal(legacyCalls, 0);
    assert.equal(gameItemCalls, 1);
    assert.equal(sent?.signData, signData);
    assert.equal(sent?.paySig, paySig);
    assert.equal(sent?.signature, userSignature);
  } finally {
    restore();
  }
});

test('GameItem failure codes without an official cancellation mapping stay unconfirmed failures', async () => {
  for (const errCode of [-2, 1, 7]) {
    const restore = installWx({
      requestMidasPaymentGameItem: (options: Record<string, unknown>) => {
        (options.fail as ((error: { errCode: number; errMsg: string }) => void) | undefined)?.({
          errCode,
          errMsg: 'payment failed',
        });
      },
    });
    try {
      assert.equal(await requestMidasPayment(directItemParams), 'failed', `errCode=${errCode}`);
    } finally {
      restore();
    }
  }
});

test('Midas bridge rejects malformed paySig without falling back to game currency', async () => {
  const signData = '{"mode":"goods","offerId":"offer-test","buyQuantity":2,"env":1,"currencyType":"CNY","platform":"android","productId":"first_charge","goodsPrice":300,"outTradeNo":"0123456789abcdef0123456789abcdef"}';
  const signature = 'b'.repeat(64);
  const directItem = {
    ...params,
    signData,
    paySig: 'bad-signature',
    signature,
  } as unknown as PayParams;
  let legacyCalls = 0;
  let gameItemCalls = 0;
  let sent: Record<string, unknown> | undefined;
  const restore = installWx({
    requestMidasPayment: (options: Record<string, unknown>) => {
      legacyCalls++;
      (options.success as (() => void) | undefined)?.();
    },
    requestMidasPaymentGameItem: (options: Record<string, unknown>) => {
      gameItemCalls++;
      sent = options;
      (options.success as (() => void) | undefined)?.();
    },
  });
  try {
    const outcome = await requestMidasPayment(directItem);
    assert.equal(outcome, 'unsupported');
    assert.equal(legacyCalls, 0);
    assert.equal(gameItemCalls, 0);
    assert.equal(sent, undefined);
  } finally {
    restore();
  }
});
