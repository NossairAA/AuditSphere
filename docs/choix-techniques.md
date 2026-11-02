# Choix techniques

Chaque décision importante du projet a été écrite avec son contexte. Cette page résume les principales.

| Sujet | Choix | Alternative | Raison | Compromis accepté |
| --- | --- | --- | --- | --- |
| Session web | BFF et session côté serveur | Jetons dans le navigateur | Un script injecté ne peut pas voler de jeton | Une base et une couche de plus |
| Publication d'événements | Outbox transactionnelle | Publication directe après commit | Aucune perte en cas d'arrêt entre l'écriture et l'envoi | Livraison au moins une fois, donc consommateurs idempotents |
| Validation de mission | Temporal et PostgreSQL | Statuts et tâches planifiées maison | L'attente durable et les reprises sont gérées | Un composant de plus à exploiter |
| Temps réel | SSE avec simple signal | Relecture périodique | Notification visible sans attendre, et le signal ne porte aucune donnée | Signal perdable, compensé par une relecture |
| Constats | Dans `audit-service` | Service dédié | Même verrou, mêmes permissions et même cycle que la mission | Service plus large |
| Actions correctives | `remediation-service` dédié | Extension d'`audit-service` | Autres utilisateurs et cycle qui survit à la clôture de la mission | Synchronisation et cohérence éventuelle |
| Preuves | Stockage objet derrière l'API | URL signées | Les droits sont revérifiés à chaque téléchargement | Le flux passe par le service |
| Hébergement | k3s sur serveur privé | Docker Compose, Kubernetes managé | Vrai Kubernetes sans coût récurrent | Un seul nœud, sans haute disponibilité |
| Exposition publique | Tunnel sortant | Ouverture de ports | Aucun port entrant sur le serveur | Dépendance à un fournisseur |
| Autorité interne | Racine hors ligne et intermédiaire | Racine dans le cluster | Une fuite du cluster n'expose pas la racine | Rotation plus manuelle |
| Sauvegardes | Tirées par un poste externe | Poussées par le serveur | Le serveur ne détient aucune clé de chiffrement | Dépend de la disponibilité du poste |
| Observabilité | Métriques permanentes, traces à la demande | Pile complète permanente | Respect de la mémoire disponible | Pas d'historique de traces |
| Démonstration | Assertion JWT signée | Mot de passe partagé, grant password | Entrée en un clic sans mot de passe exposé | Profil et réinitialisation à maintenir |

## Principes suivis

- **Découper sur des frontières réelles.** Un service existe parce que son cycle de vie ou ses utilisateurs diffèrent, pas par principe.
- **Une seule source de vérité.** Kafka et Temporal transportent et orchestrent, PostgreSQL décide.
- **Dimensionner selon l'usage mesuré.** Les outils professionnels sont conservés, réglés pour un faible volume ou démarrés à la demande.
- **Écrire ses décisions.** Chaque arbitrage garde son contexte, pour pouvoir être remis en cause plus tard.

## Une migration majeure

Spring Framework 6.2 ne recevait plus de correctifs open source et le scan des images signalait des vulnérabilités critiques. Le projet est donc passé à Spring Boot 4, Spring Framework 7 et Spring Cloud 2025.1. Le scan Trivy est ensuite devenu bloquant pour les vulnérabilités élevées ou critiques corrigeables.
