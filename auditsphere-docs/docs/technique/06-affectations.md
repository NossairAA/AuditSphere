# Jalon 3 : affectation des auditeurs

**Date :** 2026-10-16

## Parcours livré

Le responsable sélectionne des auditeurs dans le formulaire de création. La fiche affiche le responsable et les auditeurs. Seul le propriétaire disposant encore du rôle AUDIT_MANAGER peut modifier les affectations, y compris retirer tous les auditeurs. Le bouton Annuler conserve les affectations existantes.

Le retrait prend effet à la prochaine requête vers l’API, même si l’auditeur conserve une session et un JWT valides. La fiche, la liste et les participants sont filtrés côté Spring. Une page déjà affichée ne disparaît pas spontanément et les données déjà téléchargées ne peuvent pas être effacées à distance.

## Annuaire et autorisation

Spring consulte Keycloak avec le compte technique confidentiel auditsphere-directory. Ses seuls rôles explicitement attribués dans realm-management sont view-users et view-realm. Le premier permet de lire les utilisateurs, le second permet de consulter les membres du rôle AUDITOR. Aucun rôle de modification ni realm-admin n’est attribué. Le secret DIRECTORY_CLIENT_SECRET reste dans le fichier privé .env et dans le conteneur audit-service. Le navigateur ne reçoit ni ce secret ni le jeton technique.

Le client technique dispose de droits de lecture plus larges que le DTO public. L’application ne retourne que subject et name. L’annuaire complet est réservé aux responsables. Un auditeur peut lire uniquement les noms des participants à une mission autorisée. Un compte supprimé est affiché comme indisponible et peut être retiré par le responsable.

À la création et à chaque remplacement, les identifiants sélectionnés doivent figurer parmi les utilisateurs activés du rôle AUDITOR. Les appels annuaire ne sont pas mis en cache. Le périmètre validé utilise les rôles directement attribués aux comptes de démonstration. Les groupes et rôles composites ne sont pas couverts par les tests de ce jalon.

## API et cohérence

- GET /missions/auditors : annuaire réservé aux responsables.
- PUT /missions/{id}/assignments : remplacement complet de la liste, limité à 100 identifiants.
- GET /missions/{id}/participants : noms du responsable et des auditeurs, après contrôle du périmètre.
- POST /missions : validation des affectations sélectionnées dès la création.

Le BFF relaie uniquement ces routes prévues et vérifie origine et CSRF sur le PUT comme sur le POST. La décision de propriété et de rôle reste dans Spring. Un responsable non propriétaire reçoit 404. Un utilisateur sans rôle responsable reçoit 403 sur la modification.

Le remplacement est transactionnel, avec verrou d’écriture sur la mission. Un annuaire indisponible renvoie 503 et conserve les affectations. Une liste vide peut être enregistrée sans appeler l’annuaire. Le verrou sérialise les écritures, mais ne signale pas un formulaire ancien : la dernière modification acceptée remplace la précédente. Une gestion de version avec conflit explicite reste possible ultérieurement.

## Configuration locale et CI

Exécuter python3 scripts/setup-web.py --configure-only pour préparer le secret, puis docker compose up -d --build et python3 scripts/setup-web.py pour configurer les clients. Le script reste idempotent et conserve les secrets existants.

Le service ponctuel keycloak-import copie le realm privé dans un volume Docker, attribue le fichier à UID 1000 et garde le mode 600. Le processus applicatif Keycloak reste non root. Cette préparation évite de rendre le fichier source lisible par tous sur Linux lorsque l’UID du développeur diffère de celui du conteneur. Le volume realm-import contient des secrets locaux et doit être protégé comme les autres volumes.

Le workflow prépare maintenant le compte annuaire avant le smoke API. Le [run du jalon 2](https://github.com/NossairAA/AuditSphere/actions/runs/38898528506) a échoué à l’étape smoke.py. Les tests Maven et la construction Docker y ont réussi. Les logs détaillés n’ont pas pu être récupérés, la cause exacte de cet échec reste non confirmée. La compatibilité des permissions d’import est corrigée préventivement. Une nouvelle exécution distante reste nécessaire après publication.

## Vérifications

- 15 tests backend réussis, dont autorisation propriétaire, refus des auditeurs inconnus ou désactivés, panne annuaire et retrait immédiat.
- 6 tests Chromium réussis sur le serveur compilé, avec connexion réelle Keycloak et deux sessions simultanées pour vérifier le retrait d’accès.
- Smoke API avec vrais JWT réussi, contrôle TypeScript et build frontend réussis.
- Captures de la fiche et du formulaire d’affectation inspectées sur ordinateur et mobile.
- Import privé testé avec un fichier appartenant à UID 1001, illisible par UID 1000 avant préparation et lisible après copie privée.
- Scan Gitleaks et comparaison des valeurs privées locales réussis sur les fichiers destinés à Git.

## Limites

L’annuaire est lu par pages de 100 avec une limite de 5000 membres. Une liste dépassant la limite échoue explicitement plutôt que valider un résultat partiel. Un grand annuaire nécessitera une recherche paginée et une validation ciblée. La fiche résout les noms individuellement, sans cache, pour au plus 101 participants. Le changement de rôle ou la désactivation dans Keycloak ne supprime pas automatiquement une affectation métier existante. Les mécanismes de révocation IAM restent distincts du retrait d’une mission.

Pas de notification Kafka ni de nouveau microservice dans ce jalon. Les dates métier, la prévention des conflits d’édition et le durcissement de production restent ouverts.
