#!/usr/bin/env python3
"""Browser-like sign-in through the BFF public origin, then session, API and logout checks. No secret printed.

Run through scripts/k3s-check.sh, which provides APP_ORIGIN and the k3s demo accounts.
"""
import html, http.cookiejar, json, os, re, urllib.error, urllib.parse, urllib.request
import target

app = os.environ['APP_ORIGIN']
jar = http.cookiejar.CookieJar()
browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


manual = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)


def call(opener, url, data=None, headers=None):
    request = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode() if data else None, headers=headers or {})
    try:
        with opener.open(request, timeout=20) as response:
            return response.status, response.geturl(), dict(response.headers), response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, url, dict(error.headers), error.read().decode()


def sign_in(name):
    status, url, _, page = call(browser, app + '/auth/login')
    assert status == 200 and url.startswith(target.OIDC_BASE + '/realms/auditsphere/'), ('login page', status)
    action = html.unescape(re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', page).group(1))
    status, url, headers, _ = call(browser, action, {'username': name, 'password': target.secret(name.upper() + '_PASSWORD'), 'credentialId': ''})
    assert status == 200 and url == app + '/missions', ('callback', status)
    return headers


headers = sign_in('manager')
for header in ['Strict-Transport-Security', 'Content-Security-Policy', 'X-Content-Type-Options', 'Referrer-Policy']:
    assert header.lower() in {h.lower() for h in headers}, 'En-tête absent : ' + header
cookies = {c.name: c for c in jar if c.domain in urllib.parse.urlsplit(app).hostname}
session = cookies.get('__Host-auditsphere-session')
assert session and session.secure and session.path == '/' and not session.domain_specified, 'Cookie de session'
assert session.has_nonstandard_attr('HttpOnly'), 'Cookie HttpOnly'
print('PASS : connexion OIDC par le BFF, retour sur /missions, cookie __Host- sécurisé, en-têtes de sécurité présents.')

status, _, _, body = call(browser, app + '/api/session')
account = json.loads(body)
assert status == 200 and 'AUDIT_MANAGER' in account['roles'] and account['csrf'], ('session', status)
status, _, _, body = call(browser, app + '/api/missions?size=5')
listing = json.loads(body)
assert status == 200 and listing['total'] >= 1, ('missions', status)
status, _, _, _ = call(urllib.request.build_opener(), app + '/api/missions')
assert status == 401, ('missions sans session', status)
print('PASS : session du responsable, ' + str(listing['total']) + ' missions servies par la gateway, accès refusé sans session.')

status, _, _, _ = call(manual, app + '/auth/logout', {'csrf': account['csrf']}, {'Origin': app})
assert status in (302, 303, 307), ('logout', status)
status, _, _, _ = call(browser, app + '/api/session')
assert status == 401, ('session après déconnexion', status)
print('PASS : déconnexion, session supprimée côté BFF.')
