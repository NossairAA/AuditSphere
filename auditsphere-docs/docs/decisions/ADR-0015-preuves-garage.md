# ADR-0015 : preuves privées dans Garage

Date : 2026-10-21
Statut : accepté pour cette tranche

## Décision

Les fichiers de preuve sont conservés dans Garage compatible S3, les métadonnées et les permissions dans PostgreSQL. Le stockage est indépendant des conteneurs applicatifs. En local et en CI, Garage utilise un volume Docker. L’installation utilisateur utilise un homeserver, accessible uniquement sur son adresse Tailscale pour le port S3. Aucun port RPC, administration ou site web Garage n’est publié.

Le bucket est privé. Le navigateur ne reçoit ni clé S3 ni URL signée. Chaque liste et téléchargement passe par le BFF puis audit-service, qui vérifie l’accès courant à la mission et l’appartenance du contrôle. Les téléchargements sont servis en pièce jointe avec nosniff et no-store.

La clé applicative possède uniquement lecture et écriture sur le bucket des preuves. Les secrets sont générés dans les fichiers privés ignorés par Git. Le nom original ne sert jamais de clé objet, chaque preuve reçoit un UUID.

## Cohérence

Le dépôt S3 a lieu après une première vérification d’accès et avant la transaction SQL finale. Cette transaction verrouille la mission, revalide rôle, affectation, statut et version, puis insère les métadonnées et incrémente la version. Aucun appel S3 n’a lieu sous verrou métier.

Il n’existe pas de transaction atomique SQL et S3. En cas de refus SQL, une suppression compensatoire est tentée. Une interruption de processus ou un échec de compensation peut laisser un objet privé orphelin. Le journal signale les échecs de compensation, une réconciliation périodique automatique reste à réaliser. Ne jamais purger le bucket en bloc pour nettoyer un test.

## Limites

Le nœud unique n’assure pas la haute disponibilité. Les snapshots locaux ne remplacent pas une sauvegarde sur un autre support. PDF, JPEG et PNG sont reconnus par leur signature, avec contrôle du type annoncé. Il n’y a pas encore de moteur antivirus ni de validation complète du contenu documentaire. Les fichiers ne sont pas prévisualisés dans l’application.

Cette tranche ajoute des preuves, sans suppression utilisateur, réécriture de fichier, OCR ou nouvelle orchestration Temporal. Les preuves restent liées au contrôle même si sa procédure change, leur pertinence doit être revue par le responsable.
