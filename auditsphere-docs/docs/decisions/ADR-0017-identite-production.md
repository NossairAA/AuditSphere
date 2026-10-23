# ADR-0017 : identité et authentification de production

Date : 2026-10-23

Statut : accepté pour le profil de configuration, non vérifié en déploiement réel

## Contexte

Le profil Compose de production héritait du fichier d'import local de Keycloak. Cet import contenait des comptes de démonstration, dont `superadmin`. La désactivation du client de démonstration ne supprimait pas ces comptes. Le royaume ne définissait pas de politique de mot de passe ni de second facteur obligatoire.

## Options

- Supprimer les comptes de démonstration après import. Cette option laisse une fenêtre pendant laquelle ils existent et exige une mutation de comptes au démarrage.
- Importer un royaume distinct sans utilisateur, puis refuser le provisionnement si une base existante contient encore un nom de démonstration.

## Décision

Le profil de production utilise un projet Compose distinct nommé `auditsphere-production` et importe `deploy/keycloak/production-realm.json`, sans utilisateur ni client de démonstration. Les volumes locaux ne sont ainsi pas repris par défaut. `setup-web.py --production` recherche les cinq noms de démonstration et échoue avant de modifier le royaume si l'un existe. Il ne supprime aucun compte automatiquement.

Le premier superadministrateur applicatif est créé par un administrateur Keycloak depuis la console d'administration, avec une adresse personnelle vérifiée. L'administrateur attribue `SUPER_ADMIN` et envoie les actions `UPDATE_PASSWORD` et `CONFIGURE_TOTP` par courriel. Aucun mot de passe de ce compte n'est mis dans `.env`. Le compte bootstrap de Keycloak relève d'une gestion distincte et doit être retiré ou encadré après cette opération.

## Conséquences et vérification

Le profil exige un royaume et des volumes Keycloak dédiés. Une base existante contenant des comptes de démonstration est bloquée et doit être assainie manuellement après examen. Le changement de flux impose l'enrôlement OTP au prochain accès. Les identités fédérées et les autres flux d'authentification ne sont pas couverts par ce profil.

Les tests unitaires vérifient l'import sans utilisateur, le refus d'un nom de démonstration et la configuration de l'OTP. La configuration Compose fusionnée a été vérifiée avec des valeurs fictives. Un conteneur Keycloak 26.8.0 jetable a confirmé l'import, l'absence des cinq noms de démonstration, l'application du flux et une seconde exécution sans doublon. Aucun provisionnement de production ni essai de connexion d'un compte privilégié n'a été effectué.
