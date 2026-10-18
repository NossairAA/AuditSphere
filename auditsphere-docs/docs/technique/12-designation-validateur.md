# Désigner un validateur sur une mission

**Évolution :** la clôture directe décrite dans cette tranche est remplacée par [l’approbation obligatoire](13-approbation-missions.md). Les règles de gel pendant la revue et après approbation prévalent également.

**Date :** 2026-10-18
**Statut :** implémenté et vérifié localement

## Utilisation

Connecté comme manager, ouvrir une mission non clôturée. Dans Validateur de la mission, choisir Validator Demo puis Enregistrer le validateur. Le compte validator peut ensuite consulter la mission depuis sa liste. Choisir Aucun validateur et enregistrer retire cet accès, sauf si ce compte bénéficie aussi d’une affectation comme auditeur.

Le responsable ne figure pas dans sa propre liste de choix. Le backend refuse aussi son sujet, même avec les rôles AUDIT_MANAGER et AUDIT_VALIDATOR. Seul le responsable propriétaire peut modifier la désignation. La fiche expose le nom du compte désigné et traite les erreurs, les indisponibilités et les conflits de version avec rechargement.

## API et données

GET /missions/validators retourne une page d’au plus 50 comptes actifs, accessible au responsable. La pagination a lieu dans Keycloak avant le filtrage du compte courant et des comptes désactivés. Une page courte ou vide ne prouve donc pas la fin de l’annuaire.

PUT /missions/{id}/validator reçoit version et validatorSubject. Un sujet null retire la désignation. Le compte doit être actif et disposer du rôle effectif AUDIT_VALIDATOR. L’annuaire est consulté hors transaction. Une version périmée ou une mission clôturée renvoie 409. Un compte inéligible ou le propriétaire lui-même renvoie 400. Un autre responsable reçoit 404 et un non-responsable reçoit 403.

La migration V5 ajoute validator_subject et son index, avec une contrainte interdisant le propriétaire. La lecture et la liste incluent le validateur désigné lorsque son jeton contient AUDIT_VALIDATOR. La vérification côté serveur protège aussi les participants. Le BFF conserve ses protections de session, CSRF, origine, taille et débit.

Exécuter sh scripts/start-local.sh pour reconstruire le service et appliquer V5 avec le compte de migration, puis reconstruire et relancer le frontend. Les données existantes sont conservées.

## Limites

La désignation ne vaut pas approbation. Temporal, soumission, décision, relances et notifications de désignation restent à implémenter. La clôture directe existante reste disponible. Voir [ADR-0009](../decisions/ADR-0009-designation-validateur.md) pour les règles de cumul des rôles et les limites des jetons déjà émis.

## Validation

28 tests Java ciblés réussis, dont les permissions, les versions, la séparation des rôles et l’éligibilité des comptes. Les 12 scénarios navigateur passent, dont la désignation, la consultation avec le compte validator, le refus CSRF et le retrait de l’accès dans une session déjà ouverte. La compilation Next.js est réussie et la migration V5 est appliquée localement. Aucun push ni déploiement distant effectué pour cette tranche.
