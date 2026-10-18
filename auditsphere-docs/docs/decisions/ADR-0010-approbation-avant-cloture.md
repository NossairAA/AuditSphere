# ADR-0010 : Approbation obligatoire avant clôture

**Date :** 2026-10-18
**Statut :** accepté

## Décision

Cette décision remplace la clôture directe de ADR-0008 et la limite correspondante de ADR-0009. Le parcours devient DRAFT, IN_PROGRESS, PENDING_REVIEW, APPROVED, CLOSED. Le responsable propriétaire soumet une mission en cours possédant un validateur actif. Le validateur désigné, distinct du propriétaire et avec AUDIT_VALIDATOR, approuve ou refuse avec un commentaire obligatoire. Le refus remet la mission en cours. Seul le responsable propriétaire clôture après approbation.

Les opérations utilisent le verrou SQL et la version du formulaire. Les dates, affectations et désignation sont figées pendant la revue et après approbation. Le refus permet leur correction. Après clôture, la gestion des affectations reste disponible pour administrer les accès selon le comportement précédent.

La soumission revalide l’éligibilité Keycloak hors transaction, puis recontrôle la version et l’état sous verrou. La décision s’appuie sur le rôle du jeton et le sujet désigné. Une suppression de rôle dans Keycloak peut donc nécessiter l’expiration ou le renouvellement du jeton pour prendre effet.

## Persistance et limites

Les états et la décision courante sont persistés dans PostgreSQL. Le prochain envoi efface la décision précédente. Ce stockage n’est pas un historique complet de revue. Les missions déjà clôturées restent clôturées sans leur inventer d’approbation. Le déclencheur SQL bloque les nouvelles transitions vers CLOSED sans état APPROVED préalable.

Cette tranche sécurise la règle métier sans Temporal. Les notifications de revue, échéances, annulation de soumission, réouverture et historique complet restent à construire. Si le validateur est indisponible pendant une revue, aucune substitution n’est possible dans ce premier parcours. Temporal devra réutiliser ces autorisations sans rétablir la clôture directe.
