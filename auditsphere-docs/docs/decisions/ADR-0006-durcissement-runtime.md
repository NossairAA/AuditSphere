# ADR-0006 : comptes runtime et opérations bornées

**Date :** 2026-10-16
**Statut :** implémenté et testé localement

## Décision

Séparer les rôles SQL runtime, migration et administration. Exécuter Flyway dans un processus ponctuel avant le service métier. Conserver les comptes historiques de bootstrap pour la maintenance, sans les transmettre aux applications.

Sortir les validations annuaire des transactions SQL, puis revérifier propriété et version dans une transaction courte. Utiliser une version optimiste accompagnée d’un verrou court pour les affectations. Réutiliser le jeton technique et les noms, sans mettre en cache la validation des droits.

Limiter le débit par processus et borner les corps dès leur lecture. Les déploiements distribués devront ajouter une limite partagée. Conserver un verrou de session seulement sur le chemin de renouvellement, avec prolongation inactive conditionnelle sur le chemin rapide.

Préparer un profil HTTPS distinct plutôt que changer silencieusement les URL de développement.

## Conséquences

Le démarrage passe par scripts/start-local.sh, avec sauvegarde privée et migrations explicites. Le PUT des affectations exige désormais la version courante. Un formulaire ancien doit être rechargé. Le mode local reste HTTP sur boucle locale, le profil de production demande domaines, certificats et validation opérationnelle.

Voir le [guide de durcissement](../technique/08-durcissement.md).
