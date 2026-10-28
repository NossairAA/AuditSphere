"""Synthetic accounts of the demo profile (ADR-0031, ADR-0035), shared by k3s-setup-keycloak.py and demo-reset.py.

superadmin is not listed: it keeps a mandatory OTP and is never reset by the nightly job.
"""

# username: first name, last name, realm roles. Email is always <username>@example.invalid.
PROFILES = {
    'manager': ('Claire', 'Martin', ['AUDIT_MANAGER']),
    'auditor': ('Julien', 'Moreau', ['AUDITOR']),
    'outsider': ('Sarah', 'Benali', ['AUDITOR']),
    'validator': ('Thomas', 'Laurent', ['AUDIT_VALIDATOR']),
    'auditee': ('Nadia', 'Rousseau', ['AUDITEE']),
}
# Accounts reachable in one click from the sign-in page, through the JWT authorization grant.
ENTRY = ['auditor', 'validator', 'auditee']
# Identity provider that trusts the assertions signed by the BFF.
PROVIDER = 'demo-entry'


def email(username):
    return username + '@example.invalid'
