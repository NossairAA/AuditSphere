# Preuves associées aux contrôles

## Utilisation

Ouvrir le Programme d’audit puis Preuves du contrôle. Un auditeur affecté ou un superadministrateur peut joindre un PDF, JPEG ou PNG dans une mission En cours. Chaque fichier est limité à 10 Mio, chaque contrôle à 100 preuves. La justification du résultat et les pièces jointes sont indépendantes, un contrôle peut recevoir une preuve avant son résultat.

La liste conserve nom original, auteur, date, taille et empreinte SHA-256. Le nom du fichier est borné et les séparateurs de chemin et caractères de contrôle sont refusés. Le serveur détermine l’auteur et la date. Aucun secret de stockage n’est transmis au navigateur.

Le nom d'auteur vient de l'annuaire, résolu depuis le sujet authentifié. Le dépôt refuse le dépassement du quota cumulé par mission, fixé par EVIDENCE_MISSION_QUOTA_BYTES et valant 100 Mio par défaut. La somme est contrôlée après verrouillage de la mission, pour que deux dépôts simultanés ne dépassent pas le quota. L'objet déjà transmis au stockage est supprimé si la transaction est refusée.

Le responsable, les auditeurs affectés, le validateur désigné et le superadministrateur peuvent télécharger selon leurs droits sur la mission. Chaque demande recontrôle ces droits. Un retrait d’affectation ou une suppression de mission empêche les téléchargements suivants. Les ajouts sont refusés en brouillon, pendant la revue, après approbation et après clôture. Un ancien formulaire retourne 409.

Le téléchargement recalcule SHA-256 pendant le flux et journalise toute divergence de taille ou d'empreinte. Une divergence interrompt le flux, mais des octets ont pu être envoyés avant sa détection. Le lecteur doit traiter un téléchargement interrompu comme invalide. Une clé S3 limitée à la lecture reste à étudier pour réduire les droits du service qui sert les fichiers.

## API

- GET /missions/{missionId}/controls/{controlId}/evidence : liste des métadonnées.
- POST sur le même chemin : multipart contenant file et version de mission.
- GET /missions/{missionId}/controls/{controlId}/evidence/{evidenceId} : téléchargement privé en pièce jointe.

Le BFF vérifie session et CSRF avant de lire un dépôt. Il borne le corps à 11 Mio, puis le fichier à 10 Mio. La gateway réserve cette limite étendue au chemin de dépôt des preuves, les autres mutations restent limitées à 32 Kio. Spring conserve également ses limites multipart.

La migration V13 ajoute control_evidence sans remplacer les données existantes. Le compte applicatif possède SELECT et INSERT sur cette table. Chaque dépôt incrémente la version globale de mission. Le stockage réseau et les transactions sont coordonnés selon [ADR-0015](../decisions/ADR-0015-preuves-garage.md).

## Stockage local et CI

scripts/start-local.sh prépare Garage et sa clé limitée au bucket. scripts/setup-garage.py peut être rejoué sans rotation ni réinitialisation. Le bucket reste privé et les données Docker persistent après redémarrage. Les secrets et garage.toml restent dans .env et .local, exclus de Git.

Les variables utilisées sont EVIDENCE_S3_ENDPOINT, EVIDENCE_S3_REGION, EVIDENCE_S3_BUCKET, EVIDENCE_S3_ACCESS_KEY et EVIDENCE_S3_SECRET_KEY. L’adressage par chemin est activé avec EVIDENCE_S3_PATH_STYLE. La région Garage vaut garage. Un endpoint externe configuré désactive le provisionnement du stockage local.

## Serveur privé

Installation réalisée dans un dossier dédié du serveur privé. Le port 3900 est lié à l’adresse Tailscale du serveur. Les fichiers et métadonnées Garage résident dans les répertoires data et meta de cette installation. Le Mac accède au serveur par Tailscale, l’application Docker utilise cet endpoint privé.

Le modèle réutilisable se trouve dans deploy/garage/compose.yaml. Définir GARAGE_BIND_IP avec une adresse privée appropriée, copier scripts/setup-garage.py dans le répertoire scripts de l’installation et créer un .env privé avant son exécution. Ne pas réutiliser les secrets de production en CI.

La tentative de sauvegarde distante n’a pas abouti car sudo exige une authentification interactive. Aucune sauvegarde Garage n’est donc confirmée à ce stade. Le script demande désormais cette authentification avant d’arrêter le service. La connexion a été revérifiée après rétablissement du lien Tailscale : SSH et Garage répondent, dépôt, lecture et suppression authentifiés réussissent. L’accès anonyme reste refusé.

Une sauvegarde cohérente peut être produite depuis le dossier d’installation avec sh backup.sh. Le script deploy/garage/backup.sh arrête brièvement Garage, archive meta, data et les configurations privées, puis redémarre le service. L’archive contient des secrets, elle doit rester privée. Copier la sauvegarde sur un autre support. La restauration complète et une politique de rétention restent à valider avant production.

## Vérifications

53 tests du service d’audit et 4 tests gateway réussis. Compilation frontend réussie. Le parcours navigateur vérifie un dépôt supérieur à 32 Kio, le téléchargement exact par l’auditeur et le responsable, les en-têtes de téléchargement et le refus à un utilisateur extérieur. Le stockage du serveur privé est également testé par dépôt, lecture et suppression d’un objet synthétique. L’accès S3 anonyme retourne 403.

Les objets de test sont supprimés par leur clé exacte avant le nettoyage SQL des missions de test. Les données existantes sont conservées. La CI précédente du commit 3e0dbcb est verte. Cette tranche reste locale et n’est pas encore publiée.
