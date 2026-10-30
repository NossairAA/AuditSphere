package com.auditsphere.remediation;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Choice of an action owner by the mission owner. Team membership is checked at assignment. */
@RestController
@RequestMapping("/remediation/directory/auditees")
class DirectoryController {
  private final AuditeeDirectory directory;

  DirectoryController(AuditeeDirectory directory) {
    this.directory = directory;
  }

  @GetMapping
  List<AuditeeDirectory.Person> search(@RequestParam(defaultValue = "") String q, Authentication auth) {
    var actor = Actor.of(auth);
    if (!(actor.has("AUDIT_MANAGER") || actor.superAdmin())) throw Remediation.forbidden();
    if (q.length() > 100) throw Remediation.invalid();
    return directory.search(q);
  }
}
