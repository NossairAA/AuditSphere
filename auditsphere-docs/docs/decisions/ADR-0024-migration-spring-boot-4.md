# ADR-0024 : migration vers Spring Boot 4

**Statut :** Accepté le 2026-10-25
**Date :** 2026-10-25
**Auteur :** équipe AuditSphere

## Contexte

Les quatre services Spring utilisent Spring Boot 3.5.16, Spring Framework 6.2.19 et Spring Cloud 2025.0.3. Spring Boot 3.5.16 est la dernière version open source de la branche 3.5. Spring Framework 6.2 ne reçoit plus de correctifs open source. Les correctifs suivants ne sont publiés que dans le support commercial.

Le job `images` ajouté en phase 1 de l'[hébergement k3s](ADR-0023-hebergement-prive-k3s.md) analyse chaque image avec Trivy 0.75.0. L'analyse signale des vulnérabilités CRITICAL dans Spring Framework 6.2.19, Tomcat 10.1.55 et Netty 4.1.135. Les correctifs de Spring Framework ne sont publiés qu'à partir de la version 7.0.9. L'analyse est restée informative en attendant cette migration, car elle aurait bloqué toutes les pull requests sans correctif disponible.

Au 2026-10-25, les versions stables publiées sur Maven Central sont les suivantes :

- Spring Boot 4.1.2, avec Spring Framework 7.0.10.
- Spring Boot 4.0.9, avec Spring Framework 7.0.10.
- Spring Cloud 2025.1.4, construit contre Spring Boot 4.0.9.
- Spring Cloud 2026.0.0, prévu pour Spring Boot 4.1, publié seulement en RC1.

## Options évaluées

1. Rester sur Spring Boot 3.5 et accepter les vulnérabilités. Avantages : aucun changement. Inconvénients : correctifs indisponibles en open source, analyse impossible à rendre bloquante.
2. Spring Boot 4.1.2 avec Spring Cloud 2026.0.0-RC1. Avantages : branche la plus récente. Inconvénients : release train non stable pour la passerelle, contraire à la règle de n'utiliser que des versions publiées.
3. Spring Boot 4.1.2 avec Spring Cloud 2025.1.4. Avantages : versions stables. Inconvénients : combinaison non prévue par Spring Cloud, vérification de compatibilité à désactiver, risque de régression dans la passerelle.
4. Spring Boot 4.0.9 avec Spring Cloud 2025.1.4. Avantages : couple stable et testé ensemble par Spring Cloud, même Spring Framework 7.0.10 que la branche 4.1. Inconvénients : la branche 4.0 reçoit son support open source moins longtemps que la 4.1.

## Décision

Retenir l'option 4. Migrer les quatre services vers Spring Boot 4.0.9 et Spring Cloud 2025.1.4. Passer à Spring Boot 4.1 dès que Spring Cloud 2026.0.0 est publié en version stable, par une mise à jour dédiée sans nouvel ADR si elle ne change pas d'arbitrage.

Une fois la migration vérifiée, l'analyse Trivy du job `images` devient bloquante pour toute vulnérabilité HIGH ou CRITICAL qui dispose d'un correctif.

## Impacts

- **Jackson 3.** Spring Boot 4 configure Jackson 3, paquet `tools.jackson`, à la place de Jackson 2. Le code passe à `tools.jackson.databind.ObjectMapper`. `JsonProcessingException` devient `JacksonException`, une exception non vérifiée. Les annotations restent dans le paquet `com.fasterxml.jackson.annotation`. Le format JSON des événements Kafka et des réponses HTTP ne doit pas changer, ce que vérifient les tests d'intégration et les scénarios.
- **Hibernate.** Spring Boot 4.0 apporte Hibernate 7.2. `ddl-auto=validate` reste en place et confirme que les entités correspondent toujours aux migrations Flyway.
- **Spring Kafka.** Spring Kafka 4.0 et le client Kafka 4.1. L'autoconfiguration Kafka est désormais dans un module dédié : les services utilisent `spring-boot-starter-kafka`. Les sérialiseurs restent des chaînes JSON produites par le code, sans sérialiseur Jackson de Spring Kafka.
- **Temporal SDK.** Le SDK Java 1.40.0 n'utilise pas Spring Boot. Il garde sa propre dépendance à Jackson 2 pour ses convertisseurs de données. Jackson 2 reste donc présent dans `audit-service` comme dépendance transitive, sans être exposé comme bean Spring.
- **Flyway.** L'autoconfiguration Flyway est déplacée dans un module dédié. Les services utilisent `spring-boot-starter-flyway`, faute de quoi les migrations ne seraient plus exécutées au démarrage. Flyway passe en version 11.14. Il dépend lui aussi de Jackson 2, présent en 2.21.7 dans les services qui l'embarquent.
- **Kafka et lz4.** Le client Kafka 4.1.2 tire `lz4-java` 1.10.1, signalé HIGH par Trivy. Le parent Maven force la version corrigée 1.11.4 tant que Spring Boot ne gère pas un client Kafka qui l'embarque.
- **Image de base.** L'image `eclipse-temurin:21-jre` repose désormais sur Ubuntu 26.04 et contient `/usr/bin/pebble`, compilé avec une bibliothèque standard Go vulnérable. L'image finale passe à `eclipse-temurin:21-jre-noble`, sur Ubuntu 24.04 LTS, avec le même JDK et sans ce binaire.
- **Testcontainers.** Testcontainers 2.0 renomme ses artefacts, par exemple `testcontainers-postgresql` et `testcontainers-junit-jupiter`, et déplace certaines classes.
- **Spring Security.** Spring Security 7 supprime des API dépréciées de la version 6. Les configurations du serveur de ressources et de la passerelle sont adaptées si nécessaire. Les tests de sécurité existants vérifient les mêmes règles d'accès.
- **Starters et tests.** Spring Boot 4 découpe ses modules. Les tests MockMvc utilisent `spring-boot-starter-webmvc-test`. Les annotations de test changent de paquet.
- **Images.** Tomcat passe en 11.0 et Netty en 4.2. L'image de base Temurin 21 ne change pas de version de Java.

## Plan de retour arrière

La migration tient dans une seule pull request. Si une régression apparaît après fusion, la pull request est annulée par `git revert`, ce qui rétablit Spring Boot 3.5.16, Spring Cloud 2025.0.3 et l'analyse Trivy informative. Aucune migration de base de données n'accompagne ce changement, donc les données restent compatibles avec l'ancienne version. Les images précédentes restent disponibles dans le registre sous leur empreinte de commit.

## Vérifications attendues

- `./mvnw -B verify` réussit.
- La pile locale démarre, puis `smoke.py`, `test-notifications.py --faults`, `test-temporal.py --faults` et `test-browser-isolated.sh` réussissent.
- Trivy 0.75.0 ne signale aucune vulnérabilité HIGH ou CRITICAL corrigeable dans les images Java.

Le résultat réel de ces vérifications est consigné dans le [guide d'hébergement](../technique/28-hebergement-k3s.md) et dans le backlog, sous TECH-009.

## Références

- [ADR-0023 : hébergement privé avec k3s](ADR-0023-hebergement-prive-k3s.md)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Cloud : versions supportées](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)
- [Spring : politique de support](https://spring.io/support-policy)
