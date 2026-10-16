# ADR-0001 : Documentation comme code

**Statut :** Accepté  
**Date :** 2026-10-16

## Contexte

AuditSphere est un projet amené à évoluer en fonction des besoins métiers et des décisions techniques. Une documentation monolithique non versionnée risque de devenir rapidement obsolète.

## Décision

Conserver la documentation en Markdown dans Git, avec structure par domaine, journal des changements, ADR et modèles réutilisables.

## Conséquences

- Historique de modifications et validation par pull request.
- Charge de maintenance à chaque fonctionnalité.
- Possibilité future de générer un site documentaire (MkDocs ou Docusaurus).
