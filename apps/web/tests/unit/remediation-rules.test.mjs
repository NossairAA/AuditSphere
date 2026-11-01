import test from 'node:test';
import assert from 'node:assert/strict';
import {
  remediationBody, remediationError, remediationQuery, remediationRoute, validVersion,
} from '../../src/lib/remediation-rules.ts';

const a = '00000000-0000-4000-8000-000000000001';
const b = '00000000-0000-4000-8000-000000000002';

test('only the routes used by the interface are relayed', () => {
  assert.deepEqual(remediationRoute('GET', ['missions', a, 'follow-ups']), {
    kind: 'followUps', path: `/remediation/missions/${a}/follow-ups`,
  });
  assert.equal(remediationRoute('GET', ['me', 'actions']).kind, 'myActions');
  assert.equal(remediationRoute('POST', ['actions', a, 'attachments']).kind, 'upload');
  assert.equal(remediationRoute('GET', ['actions', a, 'attachments', b]).kind, 'download');
  assert.equal(remediationRoute('DELETE', ['actions', a, 'attachments', b]).kind, 'removeAttachment');
  assert.equal(remediationRoute('PUT', ['follow-ups', a, 'transition']).kind, 'followUpTransition');
  assert.equal(remediationRoute('DELETE', ['actions', a]), null);
  assert.equal(remediationRoute('GET', ['actions', '..', 'admin']), null);
  assert.equal(remediationRoute('GET', ['actions', 'not-an-id']), null);
  assert.equal(remediationRoute('GET', ['internal', 'missions', a]), null);
  assert.equal(remediationRoute('GET', ['actions', a + '%2F..']), null);
});

test('query parameters are filtered by route', () => {
  const mine = remediationQuery('myActions', new URLSearchParams('status=DONE&overdue=true&owner=x'));
  assert.equal(mine.toString(), 'status=DONE&overdue=true');
  assert.equal(remediationQuery('myActions', new URLSearchParams('status=ANY')).toString(), '');
  assert.equal(remediationQuery('auditees', new URLSearchParams('q=' + 'x'.repeat(150))).get('q').length, 100);
  assert.equal(remediationQuery('action', new URLSearchParams('status=DONE')).toString(), '');
});

test('bodies keep only allowed fields', () => {
  const input = {
    version: 2, action: 'VERIFY', comment: 'ok', description: 'D', ownerSubject: 's', dueDate: '2026-12-01',
    reason: 'r', note: 'n', status: 'VERIFIED', verifiedBy: 'forged',
  };
  assert.deepEqual(remediationBody('actionTransition', input), { version: 2, action: 'VERIFY', comment: 'ok' });
  assert.deepEqual(remediationBody('followUpTransition', { ...input, comment: 3 }), { version: 2, action: 'VERIFY', comment: null });
  assert.deepEqual(remediationBody('createAction', input), { description: 'D', ownerSubject: 's', dueDate: '2026-12-01' });
  assert.deepEqual(remediationBody('progress', input), { version: 2, note: 'n' });
  assert.deepEqual(remediationBody('updateAction', { version: 1, dueDate: '2026-12-02', reason: 'Retard fournisseur' }), {
    version: 1, dueDate: '2026-12-02', reason: 'Retard fournisseur',
  });
  assert.equal(remediationBody('action', input), null);
});

test('versions must be non-negative integers', () => {
  assert.ok(validVersion(0));
  assert.equal(validVersion(-1), false);
  assert.equal(validVersion('1'), false);
  assert.equal(validVersion(1.5), false);
});

test('errors explain the refusal without exposing upstream details', () => {
  assert.match(remediationError(409, 'actionTransition', 'COMPLETE'), /justificatif/);
  assert.match(remediationError(409, 'followUpTransition', 'REQUEST_CLOSURE'), /vérifiées/);
  assert.match(remediationError(403, 'followUpTransition', 'CLOSE'), /validateur/);
  assert.match(remediationError(403, 'actionTransition', 'VERIFY'), /vous est confiée/);
  assert.match(remediationError(404, 'download'), /Justificatif/);
  assert.match(remediationError(400, 'updateAction'), /motif/);
  assert.equal(remediationError(500, 'action'), 'Impossible de traiter cette demande');
});
