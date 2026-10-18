# Préparation du rôle validateur

**Date :** 2026-10-18
**Statut :** préparation vérifiée localement

La désignation est désormais implémentée dans la [tranche suivante](12-designation-validateur.md). Les permissions décrites ici correspondent au rôle seul, sans désignation.

Le responsable est le créateur de la mission avec le rôle AUDIT_MANAGER. Le rôle AUDIT_VALIDATOR prépare une autre personne chargée de la future approbation. Il n’accorde actuellement aucun accès global aux missions et aucun droit de gestion. La clôture directe par le responsable reste disponible.

## Installation et compte local

Une installation neuve crée le rôle et le compte validator avec scripts/init-local.py. Une installation existante utilise python3 scripts/setup-web.py. Le script crée le rôle manquant et ajoute le compte de démonstration en mode local uniquement. Son mot de passe aléatoire se trouve dans VALIDATOR_PASSWORD dans le fichier privé .env. Une seconde exécution ne réinitialise pas ce mot de passe. Un compte validator existant avec une autre adresse que celle de la démonstration provoque un refus de modification de ses droits.

Le mode --production prépare le rôle mais ne crée ni compte de démonstration ni son mot de passe. Il ne supprime pas les comptes qui existaient déjà. Le rôle ne possède pas de rôles composites donnant des droits de responsable ou d’auditeur.

## Permissions actuelles

Le compte validator peut se connecter au frontend. Sans propriété ni affectation existante, sa liste de missions est vide. La consultation directe d’une mission ou de ses participants renvoie 404. La création, la modification des affectations, des dates et du statut, ainsi que la lecture de l’annuaire des auditeurs, renvoient 403.

Les contrôles existants continuent à s’appliquer par rôle et par sujet. Un utilisateur cumulant plusieurs rôles conserve les droits de ces autres rôles. Le rôle validateur seul n’autorise pas à affecter cet utilisateur comme auditeur. Les règles de désignation comme validateur et d’interdiction de sa propre validation seront implémentées avec le futur workflow.

## Vérification

Les 25 tests Java du service audit passent. Le nouveau test vérifie les refus de gestion et l’absence de visibilité globale, y compris pour un propriétaire ne conservant que le rôle validateur. Le script smoke.py utilise un vrai jeton du compte validator via la gateway et vérifie les refus de création, de consultation hors périmètre et d’accès à l’annuaire. Il s’exécute aussi dans la CI.

L’initialisation neuve a été vérifiée dans un dossier temporaire, avec un fichier .env privé en mode 600. La configuration locale existante a été exécutée deux fois, puis la connexion avec le mot de passe conservé a été vérifiée.

Temporal, la sélection du validateur, l’approbation, le refus et les notifications de validation ne sont pas livrés dans cette tranche. Aucun changement du parcours métier ni de la base des missions n’est nécessaire pour cette préparation.
