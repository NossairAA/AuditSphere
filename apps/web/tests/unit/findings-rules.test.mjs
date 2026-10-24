import test from 'node:test';
import assert from 'node:assert/strict';
import {
  findingBody, findingError, findingPath, pendingFindingsError, requiredRole, validIds,
} from '../../src/lib/findings-rules.ts';

const mission = '00000000-0000-0000-0000-000000000001';
const finding = '00000000-0000-0000-0000-000000000002';

test('identifiers must be UUID shaped before reaching the API', () => {
  assert.ok(validIds(mission, undefined));
  assert.ok(validIds(mission, finding));
  assert.equal(validIds(mission, '../admin'), false);
  assert.equal(validIds('a', finding), false);
});

test('bodies keep only allowed fields', () => {
  const input = {
    version: 3, title: 'T', condition: 'C', severity: 'LOW', controlIds: [finding],
    createdBy: 'forged', status: 'VALIDATED', action: 'SUBMIT', comment: 'note',
  };
  const created = findingBody('POST', undefined, input);
  assert.equal('version' in created, false);
  assert.equal('createdBy' in created, false);
  assert.equal('status' in created, false);
  assert.equal(created.cause, null);
  assert.equal(findingBody('PUT', undefined, input).version, 3);
  assert.deepEqual(findingBody('PUT', 'transition', input), { version: 3, action: 'SUBMIT', comment: 'note' });
  assert.equal(findingBody('DELETE', undefined, input), null);
});

test('decisions need the manager role, writing needs the auditor role', () => {
  assert.equal(requiredRole(undefined, undefined), 'AUDITOR');
  assert.equal(requiredRole('transition', 'SUBMIT'), 'AUDITOR');
  assert.equal(requiredRole('transition', 'WITHDRAW'), 'AUDITOR');
  for (const action of ['VALIDATE', 'RETURN', 'REJECT', 'REOPEN'])
    assert.equal(requiredRole('transition', action), 'AUDIT_MANAGER');
  assert.equal(requiredRole('transition', ['VALIDATE']), 'AUDITOR');
});

test('paths target the audit-service findings API', () => {
  assert.equal(findingPath(mission), `/missions/${mission}/findings`);
  assert.equal(findingPath(mission, finding, 'history'), `/missions/${mission}/findings/${finding}/history`);
  assert.equal(findingPath(mission, finding, undefined, 4), `/missions/${mission}/findings/${finding}?version=4`);
});

test('errors are explained without upstream messages', () => {
  assert.match(findingError(409, 'POST'), /En cours/);
  assert.match(findingError(409, 'PUT', 'transition', 'SUBMIT'), /cause et la recommandation/);
  assert.match(findingError(409, 'PUT', 'transition', 'VALIDATE'), /Rechargez/);
  assert.match(findingError(403, 'PUT', 'transition', 'VALIDATE'), /soumis ce constat/);
  assert.equal(findingError(403, 'POST'), 'Votre rôle ne permet pas cette action');
  assert.match(findingError(404, 'GET'), /introuvable/);
});

test('pending findings are forwarded only from the expected 409 body', () => {
  assert.deepEqual(pendingFindingsError({ code: 'FINDINGS_PENDING', pending: 2 }), {
    code: 'FINDINGS_PENDING', pending: 2,
    error: '2 constats attendent encore une décision. Validez-les ou rejetez-les avant de continuer.',
  });
  assert.match(pendingFindingsError({ code: 'FINDINGS_PENDING', pending: 1 }).error, /^Un constat/);
  for (const body of [null, 'x', {}, { code: 'OTHER', pending: 1 }, { code: 'FINDINGS_PENDING', pending: 0 }, { code: 'FINDINGS_PENDING', pending: '2' }])
    assert.equal(pendingFindingsError(body), null);
});
