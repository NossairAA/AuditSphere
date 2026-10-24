# Reprise de la gateway après changement d’adresse

**Date :** 2026-10-19

La première CI Temporal du commit 060002d a échoué après redémarrage d’audit-service, pendant l’arrêt de Temporal. La gateway tentait encore de joindre une ancienne adresse Docker et renvoyait NoRouteToHost, alors que le processus audit continuait ses tentatives de publication.

Le client HTTP de la gateway limite désormais le cache DNS positif à cinq secondes, sans durée minimale ni cache négatif. La connexion TCP est bornée à trois secondes. Les routes peuvent retrouver la nouvelle adresse sans redémarrage manuel de la gateway. Une interruption peut encore produire une erreur transitoire pendant ce délai.

Le scénario de pannes Temporal continue de vérifier la disponibilité réelle de la mission via la gateway après le redémarrage. La CI distante du commit 7e4c707 a réussi, y compris le scénario Temporal et les parcours navigateur, sur le run 39233384313.

## Registre de révocation et redémarrage d’audit-service

**Date :** 2026-10-24

La CI du commit 0871cbe a échoué sur le scénario Temporal avec `Timeout: API restart while Temporal down`. Le filtre de révocation ajouté par C10 consulte `/account/security` avant chaque requête authentifiée. Son client HTTP n’utilisait pas le résolveur borné des routes. Après un redémarrage, audit-service pouvait recevoir une nouvelle adresse Docker. Le filtre visait alors l’ancienne adresse jusqu’à son délai de deux secondes et la gateway renvoyait 503 à chaque requête.

Reproduction locale avant correctif : le scénario échoue de la même façon, l’adresse d’audit-service passe de 172.18.0.11 à 172.18.0.8. Le DNS Docker vu depuis la gateway donne la nouvelle adresse mais `GET /missions` renvoie 503. Un redémarrage de la seule gateway rétablit 200.

Le filtre de révocation utilise désormais le même client borné que les routes : cache DNS positif de cinq secondes, sans durée minimale ni cache négatif, connexion TCP limitée à trois secondes. Le comportement de refus fermé est inchangé.

Le test `GatewayDnsTest` vérifie que le client du filtre de révocation et celui des routes portent le même réglage : cache positif de cinq secondes, caches minimal et négatif nuls, connexion de trois secondes. Il contrôle aussi qu’un client par défaut ne respecte pas cette borne. Ce test vérifie la configuration, pas une vraie résolution DNS. Le scénario Temporal avec pannes reste la vérification de bout en bout.

Vérifications locales : onze tests gateway réussis dans le conteneur Maven de build, dont les deux tests `GatewayDnsTest`. Avec un client par défaut remis temporairement dans le filtre, `GatewayDnsTest` échoue comme attendu. Image gateway reconstruite, puis scénario Temporal avec pannes réussi alors que l’adresse d’audit-service changeait de 172.18.0.8 à 172.18.0.12. La CI distante reste à observer.

## Nettoyage des données de test et suites navigateur

La CI du commit 129393a a échoué sur le parcours `lifecycle`. Le nettoyage Playwright de la suite précédente arrête puis relance audit-service. La suite suivante ne vérifiait que le frontend et la santé de la gateway. Elle se connectait pendant le démarrage d’audit-service, qui dure environ seize secondes en CI, et la page des missions restait en erreur.

Le script de nettoyage attend désormais que `/actuator/health` d’audit-service réponde 200, depuis l’intérieur du conteneur, avant de rendre la main. Il échoue après 120 secondes sans réponse.

L’attente est isolée dans `scripts/service_health.py`. Seule une ligne de statut HTTP 200 est acceptée. Une réponse 503, une sortie vide ou un message d’erreur contenant 200 sont refusés. Quatre tests `test_service_health.py` vérifient ce statut, les nouvelles tentatives jusqu’à la réponse, le retour immédiat d’un service déjà prêt et l’échec à l’échéance. Ils n’exécutent pas Docker et ne prouvent pas que le script de nettoyage appelle cette attente.

Vérification locale : nettoyage d’un identifiant fictif terminé en sept secondes, suivi immédiatement d’un `GET /missions` à 200 via la gateway. Les suites navigateur isolées n’ont pas été rejouées localement car le port 3000 était occupé par le frontend de développement. Ce point reste à confirmer par la CI distante.
