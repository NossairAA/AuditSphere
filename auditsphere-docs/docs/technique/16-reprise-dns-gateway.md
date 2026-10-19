# Reprise de la gateway après changement d’adresse

**Date :** 2026-10-19

La première CI Temporal du commit 060002d a échoué après redémarrage d’audit-service, pendant l’arrêt de Temporal. La gateway tentait encore de joindre une ancienne adresse Docker et renvoyait NoRouteToHost, alors que le processus audit continuait ses tentatives de publication.

Le client HTTP de la gateway limite désormais le cache DNS positif à cinq secondes, sans durée minimale ni cache négatif. La connexion TCP est bornée à trois secondes. Les routes peuvent retrouver la nouvelle adresse sans redémarrage manuel de la gateway. Une interruption peut encore produire une erreur transitoire pendant ce délai.

Le scénario de pannes Temporal continue de vérifier la disponibilité réelle de la mission via la gateway après le redémarrage. La validation distante sera relancée avec ce correctif.
