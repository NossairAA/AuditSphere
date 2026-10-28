#!/usr/bin/env python3
"""Add 40 synthetic audit missions through the local API. Dry run unless --apply.

State is reconciled from the API on every run. A private manifest anchors relative dates and
records IDs for review, but is never the only source of truth after an interrupted POST.
"""
import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

# Cloudflare's browser integrity check refuses the default Python-urllib agent on the public names
# (phase 10, lot B, error 1010). Every opener, including the one behind urlopen, sends a named agent.
_opener_init = urllib.request.OpenerDirector.__init__


def _named_opener(self):
    _opener_init(self)
    self.addheaders = [('User-agent', 'AuditSphere-checks')]


urllib.request.OpenerDirector.__init__ = _named_opener

ROOT = Path(__file__).resolve().parents[1]
# Les valeurs par défaut visent la pile locale. Le cluster k3s les remplace par des variables d'environnement.
MANIFEST = Path(os.environ.get('SEED_MANIFEST', ROOT / '.local' / 'demo-missions-manifest.json'))
BASE = os.environ.get('SEED_API_BASE', 'http://localhost:8080')
TOKEN_URL = os.environ.get('SEED_TOKEN_URL', 'http://localhost:8180/realms/auditsphere/protocol/openid-connect/token')
ACCOUNTS = Path(os.environ.get('SEED_ACCOUNTS_FILE', ROOT / '.env'))
IDENT = 'auditsphere-demo-expansion-v1'

# title, perimeter, audited process, primary asset, risk themes
CASES = [
    ('Gouvernance des délégations de signature', 'Gouvernance', 'délégations de signature', 'engagements contractuels', ('délégation périmée', 'dépassement des seuils', 'absence de piste de décision')),
    ('Maîtrise du référencement fournisseurs', 'Achats', 'référencement fournisseurs', 'base fournisseurs', ('fournisseur non qualifié', 'doublon de tiers', 'conflit d’intérêts non déclaré')),
    ('Séparation des tâches dans les achats', 'Achats', 'commandes et approbations', 'commandes d’achat', ('auto-approbation', 'fractionnement des commandes', 'contournement du budget')),
    ('Fiabilité des rapprochements bancaires', 'Finance', 'rapprochements bancaires', 'comptes bancaires', ('écart non résolu', 'écriture non autorisée', 'retard de rapprochement')),
    ('Revue du processus de clôture mensuelle', 'Finance', 'clôture comptable', 'écritures de clôture', ('écriture tardive', 'justificatif absent', 'anomalie non escaladée')),
    ('Contrôle des immobilisations corporelles', 'Finance', 'suivi des immobilisations', 'registre des actifs', ('actif manquant', 'amortissement erroné', 'sortie non enregistrée')),
    ('Sécurisation des remboursements clients', 'Ventes', 'remboursements clients', 'avoirs et remboursements', ('remboursement indu', 'double remboursement', 'validation insuffisante')),
    ('Intégrité des remises commerciales', 'Ventes', 'octroi des remises', 'conditions tarifaires', ('remise excessive', 'accord expiré', 'traçabilité incomplète')),
    ('Qualité des données du référentiel clients', 'Données', 'gestion des données clients', 'référentiel clients', ('doublon client', 'coordonnées obsolètes', 'modification non autorisée')),
    ('Suivi des réclamations et litiges clients', 'Service client', 'traitement des réclamations', 'dossiers de litige', ('délai de réponse dépassé', 'résolution non documentée', 'escalade omise')),
    ('Gestion des accès aux applications financières', 'Systèmes d’information', 'habilitations financières', 'comptes applicatifs', ('droits excessifs', 'compte dormant', 'revue des accès absente')),
    ('Revue des changements applicatifs', 'Systèmes d’information', 'mises en production', 'changements applicatifs', ('changement non testé', 'approbation manquante', 'retour arrière impréparé')),
    ('Sauvegarde et restauration des données critiques', 'Continuité', 'sauvegardes informatiques', 'données critiques', ('sauvegarde incomplète', 'restauration non testée', 'copie indisponible')),
    ('Gestion des incidents de cybersécurité', 'Cybersécurité', 'réponse aux incidents', 'alertes de sécurité', ('incident non qualifié', 'confinement tardif', 'preuves techniques perdues')),
    ('Protection des postes de travail', 'Cybersécurité', 'sécurité des postes', 'parc bureautique', ('correctif absent', 'chiffrement désactivé', 'agent de protection inactif')),
    ('Suivi des contrats de sous-traitance', 'Tiers', 'suivi des sous-traitants', 'contrats de service', ('clause manquante', 'échéance non suivie', 'performance non évaluée')),
    ('Évaluation des prestataires critiques', 'Tiers', 'évaluation des prestataires', 'services externalisés', ('dépendance non mesurée', 'contrôle de sécurité insuffisant', 'plan de sortie absent')),
    ('Gestion des déclarations de conflits d’intérêts', 'Éthique', 'déclarations d’intérêts', 'déclarations annuelles', ('déclaration omise', 'situation non arbitrée', 'registre incomplet')),
    ('Dispositif de prévention de la fraude', 'Éthique', 'prévention de la fraude', 'signalements internes', ('signal faible ignoré', 'enquête non tracée', 'mesure corrective tardive')),
    ('Conservation des documents réglementaires', 'Conformité', 'archivage documentaire', 'documents réglementaires', ('destruction prématurée', 'accès non maîtrisé', 'indexation incorrecte')),
    ('Traçabilité des consentements clients', 'Conformité', 'gestion des consentements', 'préférences clients', ('consentement non prouvé', 'retrait non appliqué', 'durée de conservation dépassée')),
    ('Préparation des déclarations fiscales', 'Fiscalité', 'déclarations fiscales', 'données déclaratives', ('montant inexact', 'dépôt tardif', 'justificatif incomplet')),
    ('Contrôle des variables de paie', 'Ressources humaines', 'variables de paie', 'éléments variables', ('prime non autorisée', 'absence mal prise en compte', 'double paiement')),
    ('Gestion des entrées et sorties du personnel', 'Ressources humaines', 'mouvements du personnel', 'dossiers collaborateurs', ('accès non retiré', 'pièce contractuelle absente', 'mise à jour tardive')),
    ('Suivi des formations obligatoires', 'Ressources humaines', 'formations obligatoires', 'habilitations métiers', ('formation échue', 'participant non suivi', 'preuve de présence absente')),
    ('Inventaire des équipements nomades', 'Actifs', 'équipements nomades', 'ordinateurs et téléphones', ('équipement perdu', 'attribution non tracée', 'restitution non effectuée')),
    ('Sécurité physique des salles techniques', 'Installations', 'accès aux salles techniques', 'locaux techniques', ('entrée non autorisée', 'journal d’accès incomplet', 'alarme non testée')),
    ('Maintenance préventive des installations', 'Installations', 'maintenance préventive', 'équipements de bâtiment', ('entretien omis', 'panne récurrente', 'intervention non documentée')),
    ('Gestion des stocks à rotation lente', 'Logistique', 'rotation des stocks', 'articles stockés', ('obsolescence non détectée', 'valorisation inexacte', 'sortie non autorisée')),
    ('Contrôle des expéditions et retours', 'Logistique', 'expéditions et retours', 'bons de livraison', ('expédition erronée', 'retour non enregistré', 'écart de quantité')),
    ('Fiabilité des prévisions de trésorerie', 'Trésorerie', 'prévisions de trésorerie', 'flux prévisionnels', ('hypothèse obsolète', 'écart non analysé', 'besoin de liquidité sous-estimé')),
    ('Autorisation des paiements exceptionnels', 'Trésorerie', 'paiements exceptionnels', 'ordres de paiement', ('paiement non approuvé', 'bénéficiaire modifié', 'urgence non justifiée')),
    ('Gestion des projets d’investissement', 'Projets', 'projets d’investissement', 'dossiers d’investissement', ('dépassement de coût', 'jalon non validé', 'bénéfice non mesuré')),
    ('Suivi des actions correctives d’audit', 'Gouvernance', 'suivi des plans d’action', 'actions correctives', ('action échue', 'clôture sans preuve', 'responsable non désigné')),
    ('Qualité des indicateurs de pilotage', 'Gouvernance', 'production des indicateurs', 'tableaux de bord', ('source erronée', 'calcul non revu', 'indicateur publié en retard')),
    ('Gestion des accès aux données sensibles', 'Données', 'accès aux données sensibles', 'jeux de données protégés', ('accès excessif', 'export non autorisé', 'journalisation insuffisante')),
    ('Revue des interfaces entre applications', 'Systèmes d’information', 'échanges applicatifs', 'flux d’interface', ('flux interrompu', 'rejet non traité', 'donnée dupliquée')),
    ('Résilience des services numériques', 'Continuité', 'continuité des services', 'services numériques', ('bascule non testée', 'capacité insuffisante', 'dépendance non documentée')),
    ('Contrôle des dépenses de déplacement', 'Dépenses', 'frais de déplacement', 'demandes de remboursement', ('dépense hors politique', 'justificatif manquant', 'remboursement en double')),
    ('Conformité des engagements environnementaux', 'Durabilité', 'suivi environnemental', 'données de consommation', ('mesure inexacte', 'objectif non suivi', 'publication non justifiée')),
]
assert len(CASES) == 40

class ApiError(RuntimeError):
    pass

class Client:
    def __init__(self, passwords):
        self.passwords = passwords
        self.tokens = {}

    def login(self, name):
        form = urllib.parse.urlencode({'client_id': 'auditsphere-demo', 'grant_type': 'password',
                                       'username': name, 'password': self.passwords[name]}).encode()
        req = urllib.request.Request(TOKEN_URL,
                                     data=form, headers={'Content-Type': 'application/x-www-form-urlencoded'})
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                self.tokens[name] = json.load(response)['access_token']
        except urllib.error.HTTPError as exc:
            raise ApiError(f'Connexion {name}: HTTP {exc.code}') from None

    def subject(self, name):
        if name not in self.tokens:
            self.login(name)
        payload = self.tokens[name].split('.')[1]
        return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['sub']

    def call(self, name, path, body=None, method=None):
        if name not in self.tokens:
            self.login(name)
        for attempt in range(3):
            time.sleep(0.3)
            req = urllib.request.Request(BASE + path,
                                         data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
                                         method=method,
                                         headers={'Authorization': 'Bearer ' + self.tokens[name],
                                                  'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(req, timeout=20) as response:
                    raw = response.read()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as exc:
                if exc.code == 401 and attempt == 0:
                    self.login(name)
                    continue
                if exc.code == 429 and attempt < 2:
                    time.sleep(min(60, int(exc.headers.get('Retry-After', '60')) + 1))
                    continue
                raise ApiError(f'{method or "GET"} {path}: HTTP {exc.code}') from None
            except (OSError, TimeoutError) as exc:
                raise ApiError(f'{method or "GET"} {path}: connexion interrompue ({type(exc).__name__})') from None
        raise ApiError(f'{method or "GET"} {path}: tentatives épuisées')


def passwords_from_env():
    # This file is local and ignored by Git. Never print values or HTTP response bodies.
    values = {}
    for line in ACCOUNTS.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key] = value.strip().strip('"').strip("'")
    return {name: values[name.upper() + '_PASSWORD'] for name in ('manager', 'auditor', 'outsider', 'validator')}


def save_manifest(state):
    MANIFEST.parent.mkdir(exist_ok=True)
    os.chmod(MANIFEST.parent, 0o700)
    temporary = MANIFEST.with_suffix('.tmp')
    with temporary.open('w') as stream:
        os.chmod(temporary, 0o600)
        json.dump(state, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    os.replace(temporary, MANIFEST)


def all_missions(api):
    result = []
    page = 0
    while True:
        batch = api.call('manager', f'/missions?page={page}&size=100')
        result.extend(batch['items'])
        if len(result) >= batch['total']:
            return result
        page += 1


def kind(index):
    if index < 8:
        return 'DRAFT'
    if index < 20:
        return ('REJECTED' if index in (9, 13, 17) else
                'WITHDRAWN' if index in (11, 18) else 'IN_PROGRESS')
    if index < 27:
        return 'PENDING_REVIEW'
    if index < 34:
        return 'APPROVED'
    return 'CLOSED'


def dates(anchor, index):
    # A mix of overdue, current and future plans, all relative to the first run.
    if index == 0:
        return None, None
    if index in (7, 19, 26):
        return (anchor - dt.timedelta(days=14)).isoformat(), anchor.isoformat()
    if index < 8:
        start, end = ((-35, -5) if index % 3 == 0 else (7 + index, 28 + index))
    elif index < 20:
        start, end = ((-70, -10 - index % 7) if index % 2 == 0 else (-12, 16 + index))
    elif index < 27:
        start, end = ((-50, -(index % 12) - 1) if index % 2 else (-20, 12))
    else:
        start, end = -90 - index, -15 - index % 20
    return ((anchor + dt.timedelta(days=start)).isoformat(),
            (anchor + dt.timedelta(days=end)).isoformat())


def reconcile_program(api, mission, index, case, assignee):
    mid = mission['id']
    title, perimeter, process, asset, themes = case
    program = api.call('manager', f'/missions/{mid}/program')
    for number, theme in enumerate(themes, 1):
        risk_title = f'{number}. {theme[0].upper() + theme[1:]}'
        matches = [r for r in program['risks'] if r['title'] == risk_title]
        if len(matches) > 1:
            raise ApiError(f'{title}: risque ambigu {number}')
        if not matches:
            program = api.call('manager', f'/missions/{mid}/risks', {
                'version': program['mission']['version'], 'title': risk_title,
                'description': f'Risque fictif de {theme} dans le processus de {process}, avec impact possible sur {asset}.',
                'probability': 1 + (index + number) % 5, 'impact': 1 + (index + number * 2) % 5}, 'PUT')
    risks = sorted(program['risks'], key=lambda r: r['title'])
    controls = [
        (f'Valider les autorisations relatives à {asset}', f'Sur un échantillon fictif de dossiers de {process}, comparer les autorisations aux seuils et aux délégations en vigueur.', [risks[0]['id']]),
        (f'Rapprocher les enregistrements de {asset}', f'Comparer les enregistrements source et cible de {asset}, puis documenter les écarts et leur résolution.', [risks[1]['id']]),
        (f'Examiner les exceptions du processus de {process}', f'Inspecter le registre des exceptions, les validations et les délais de traitement du processus de {process}.', [risks[2]['id']]),
        (f'Vérifier la revue périodique de {asset}', f'Contrôler la fréquence, les responsables et la trace de revue de {asset} sur la période fictive.', [r['id'] for r in risks]),
    ]
    for objective, procedure, risk_ids in controls:
        matches = [c for c in program['controls'] if c['objective'] == objective]
        if len(matches) > 1:
            raise ApiError(f'{title}: contrôle ambigu')
        if not matches:
            program = api.call('manager', f'/missions/{mid}/controls', {
                'version': program['mission']['version'], 'objective': objective,
                'procedure': procedure, 'riskIds': risk_ids}, 'PUT')
    if kind(index) != 'DRAFT':
        mission = api.call('manager', f'/missions/{mid}')
        if mission['status'] == 'DRAFT':
            mission = api.call('manager', f'/missions/{mid}/status',
                               {'version': mission['version'], 'status': 'IN_PROGRESS'}, 'PUT')
        if mission['status'] == 'IN_PROGRESS':
            program = api.call('manager', f'/missions/{mid}/program')
            for number, control in enumerate(program['controls']):
                if kind(index) == 'IN_PROGRESS' and number >= 2:
                    continue
                if control['result'] is None:
                    outcome = ('CONFORMING', 'NON_CONFORMING', 'NOT_APPLICABLE')[(index + number) % 3]
                    comment = {
                        'CONFORMING': 'Simulation : contrôle satisfait sur l’échantillon fictif examiné.',
                        'NON_CONFORMING': 'Simulation : écart fictif identifié, action corrective à définir.',
                        'NOT_APPLICABLE': 'Simulation : cas non applicable à la période fictive sélectionnée.',
                    }[outcome]
                    program = api.call(assignee, f'/missions/{mid}/results', {
                        'version': program['mission']['version'], 'controlId': control['id'],
                        'outcome': outcome, 'comment': comment}, 'PUT')
    return api.call('manager', f'/missions/{mid}')


def reconcile_status(api, mission, index, validator_subject):
    mid = mission['id']
    target = kind(index)
    if target in ('DRAFT', 'IN_PROGRESS'):
        if mission['status'] != target:
            raise ApiError(f'{mission["title"]}: statut inattendu {mission["status"]}')
        return mission
    if target in ('REJECTED', 'WITHDRAWN') and mission['status'] == 'IN_PROGRESS':
        if (target == 'REJECTED' and mission['reviewDecision'] == 'REJECTED') or \
           (target == 'WITHDRAWN' and mission['withdrawnAt']):
            return mission
    if mission['status'] == 'IN_PROGRESS':
        if mission['validatorSubject'] != validator_subject:
            if mission['validatorSubject'] is not None:
                raise ApiError(f'{mission["title"]}: validateur différent')
            mission = api.call('manager', f'/missions/{mid}/validator',
                               {'version': mission['version'], 'validatorSubject': validator_subject}, 'PUT')
        mission = api.call('manager', f'/missions/{mid}/submission', {'version': mission['version']}, 'PUT')
    if target == 'PENDING_REVIEW':
        if mission['status'] != target:
            raise ApiError(f'{mission["title"]}: statut inattendu {mission["status"]}')
        return mission
    if mission['status'] == 'PENDING_REVIEW':
        if target == 'WITHDRAWN':
            mission = api.call('manager', f'/missions/{mid}/withdrawal', {
                'version': mission['version'], 'reason': 'Simulation : retrait pour revoir le périmètre avant une nouvelle soumission.'}, 'PUT')
        else:
            decision = 'REJECTED' if target == 'REJECTED' else 'APPROVED'
            mission = api.call('validator', f'/missions/{mid}/review', {
                'version': mission['version'], 'decision': decision,
                'comment': ('Simulation : corrections demandées sur les écarts fictifs.' if decision == 'REJECTED'
                            else 'Simulation : programme fictif approuvé pour démonstration.')}, 'PUT')
    if target in ('REJECTED', 'WITHDRAWN'):
        if mission['status'] != 'IN_PROGRESS':
            raise ApiError(f'{mission["title"]}: décision inattendue')
    elif target == 'APPROVED':
        if mission['status'] != 'APPROVED':
            raise ApiError(f'{mission["title"]}: approbation inattendue')
    elif target == 'CLOSED':
        if mission['status'] == 'APPROVED':
            mission = api.call('manager', f'/missions/{mid}/status',
                               {'version': mission['version'], 'status': 'CLOSED'}, 'PUT')
        if mission['status'] != 'CLOSED':
            raise ApiError(f'{mission["title"]}: clôture inattendue')
    return mission


def main():
    parser = argparse.ArgumentParser(description='Préparer 40 missions entièrement fictives')
    parser.add_argument('--apply', action='store_true', help='Créer ou reprendre les missions via l’API locale')
    args = parser.parse_args()
    if not args.apply:
        counts = {status: sum(kind(i) == status for i in range(40)) for status in
                  ('DRAFT', 'IN_PROGRESS', 'REJECTED', 'WITHDRAWN', 'PENDING_REVIEW', 'APPROVED', 'CLOSED')}
        print(f'Plan synthétique : 40 missions, 120 risques, 160 contrôles. Scénarios : {counts}.')
        print('Aucune écriture. Exécuter avec --apply après sauvegarde et préflight.')
        return
    if not ACCOUNTS.exists():
        raise ApiError('Fichier des comptes absent')
    if MANIFEST.exists():
        state = json.loads(MANIFEST.read_text())
        if state.get('seedId') != IDENT or not isinstance(state.get('missions'), dict):
            raise ApiError('Manifeste privé incompatible')
        anchor = dt.date.fromisoformat(state['anchorDate'])
    else:
        anchor = dt.datetime.now(dt.timezone.utc).date()
        state = {'seedId': IDENT, 'anchorDate': anchor.isoformat(), 'missions': {}}
    save_manifest(state)
    api = Client(passwords_from_env())
    subjects = {name: api.subject(name) for name in ('auditor', 'outsider', 'validator')}
    existing = all_missions(api)
    by_title = {}
    for item in existing:
        by_title.setdefault(item['title'], []).append(item)
    missing = sum(case[0] not in by_title for case in CASES)
    if len(existing) + missing > 100:
        raise ApiError(f'Capacité prudente dépassée : {len(existing)} missions visibles + {missing} à créer > 100')
    for index, case in enumerate(CASES):
        title, perimeter, process, asset, themes = case
        matches = by_title.get(title, [])
        if len(matches) > 1:
            raise ApiError(f'Titre ambigu : {title}')
        if matches and IDENT not in matches[0]['description']:
            raise ApiError(f'Titre déjà utilisé hors de ce jeu : {title}')
        saved = state['missions'].get(str(index))
        if saved and matches and saved != matches[0]['id']:
            raise ApiError(f'Manifeste incohérent : {title}')
        if saved and not matches:
            raise ApiError(f'Mission du manifeste absente : {title}')
        if str(index) in state.get('completed', []):
            print(f'{index + 1}/40 : déjà créé, conservé sans modification', flush=True)
            continue
        assignee = 'auditor' if index % 2 == 0 else 'outsider'
        if matches:
            mission = matches[0]
        else:
            start, end = dates(anchor, index)
            mission = api.call('manager', '/missions', {
                'title': title, 'description': f'Simulation entièrement fictive ({IDENT}). Revue du processus de {process} et de {asset}. Aucun audit réel ni preuve réelle.',
                'perimeter': perimeter, 'assigneeSubjects': [subjects[assignee]],
                'plannedStartDate': start, 'plannedEndDate': end}, 'POST')
            by_title[title] = [mission]
        state['missions'][str(index)] = mission['id']
        save_manifest(state)
        mission = reconcile_program(api, mission, index, case, assignee)
        reconcile_status(api, mission, index, subjects['validator'])
        state.setdefault('completed', []).append(str(index))
        save_manifest(state)
        print(f'{index + 1}/40 : {title} [{kind(index)}]', flush=True)
    print('Terminé : 40 missions fictives réconciliées. Aucun compte ni mission existante supprimé.')


if __name__ == '__main__':
    try:
        main()
    except (ApiError, KeyError, ValueError, OSError) as exc:
        # Never include response bodies, passwords or JWTs in errors.
        raise SystemExit(f'Arrêt sûr : {exc}') from None
