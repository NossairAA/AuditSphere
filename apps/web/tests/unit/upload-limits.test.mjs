import test from 'node:test';
import assert from 'node:assert/strict';
import { acquireUpload, boundedBody } from '../../src/lib/upload-limits.ts';
test('per-user and global admission, with idempotent release', () => {
  const releases = [];
  try {
    releases.push(acquireUpload('a'), acquireUpload('a'));
    assert.equal(acquireUpload('a'), null);
    for (let i = 0; i < 6; i++) releases.push(acquireUpload('b' + i));
    assert.ok(releases.every(Boolean));
    assert.equal(acquireUpload('c'), null);
    releases[0](); releases[0]();
    const slot = acquireUpload('c'); assert.ok(slot); releases.push(slot);
    assert.equal(acquireUpload('d'), null);
  } finally { releases.forEach(release => release?.()); }
});
test('actual bytes are bounded without content length', async () => {
  await assert.rejects(boundedBody(new Request('http://localhost', { method: 'POST', body: '1234' }), 3), /size/);
});
test('stalled stream times out and is cancelled', async () => {
  let cancelled = false;
  const body = new ReadableStream({ cancel() { cancelled = true; } });
  await assert.rejects(boundedBody(new Request('http://localhost', { method: 'POST', body, duplex: 'half' }), 100, 10), /timeout/);
  assert.equal(cancelled, true);
});
test('normal body is preserved', async () => {
  assert.equal(new TextDecoder().decode(await boundedBody(new Request('http://localhost', { method: 'POST', body: 'abc' }))), 'abc');
});
