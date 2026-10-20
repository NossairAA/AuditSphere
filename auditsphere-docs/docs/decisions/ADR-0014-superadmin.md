# ADR-0014 : Administration globale et dérogations explicites

**État : accepté le 2026-10-20.**

## Décision

Introduire un rôle applicatif SUPER_ADMIN donnant accès à toutes les missions sans changer leur propriétaire. Réserver les dérogations à un endpoint distinct, avec motif obligatoire, contrôle de version et journal atomique. L’utilisateur a explicitement demandé la possibilité d’outrepasser la séparation des responsabilités.

Maintenir les règles ordinaires sur leurs endpoints. Conserver les contraintes structurelles de dates. Autoriser une approbation personnelle ou une clôture exceptionnelle seulement via une dérogation tracée. Utiliser une suppression logique pour conserver les cycles et les traces des missions déjà traitées.

## Conséquences

Le journal et la décision Temporal sont transactionnels avec la mission. Le compte SQL applicatif ne peut pas effacer le journal. Le rôle n’accorde aucun privilège d’infrastructure ni de gestion des identités. La restauration après suppression et une console globale du journal restent hors périmètre.

Cette décision étend ADR-0010 et ADR-0013 pour le seul parcours de dérogation du superadministrateur. Les utilisateurs ordinaires conservent leurs règles existantes.
