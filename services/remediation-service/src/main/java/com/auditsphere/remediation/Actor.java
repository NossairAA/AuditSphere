package com.auditsphere.remediation;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/** The connected user and its realm roles, as converted by SecurityConfig. */
record Actor(String subject, Set<String> roles) {
  static Actor of(Authentication auth) {
    var roles = new HashSet<String>();
    for (var authority : auth.getAuthorities())
      if (authority.getAuthority().startsWith("ROLE_"))
        roles.add(authority.getAuthority().substring(5));
    return new Actor(auth.getName(), Set.copyOf(roles));
  }

  boolean has(String role) {
    return roles.contains(role);
  }

  boolean superAdmin() {
    return has("SUPER_ADMIN");
  }

  /** Team of a mission as frozen at approval. Validator may be null after an override. */
  record Team(String owner, String validator, Set<String> auditors) {
    boolean member(String subject) {
      return owner.equals(subject) || subject.equals(validator) || auditors.contains(subject);
    }
  }

  boolean manager(Team team) {
    return has("AUDIT_MANAGER") && subject.equals(team.owner());
  }

  boolean auditor(Team team) {
    return has("AUDITOR") && team.auditors().contains(subject);
  }

  boolean validator(Team team) {
    return has("AUDIT_VALIDATOR") && subject.equals(team.validator());
  }

  boolean reads(Team team) {
    return superAdmin() || manager(team) || auditor(team) || validator(team);
  }

  /** False for the regular holder, true for a SUPER_ADMIN override, 403 otherwise. */
  boolean authorize(boolean allowed) {
    if (allowed) return false;
    if (superAdmin()) return true;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
