package com.auditsphere.remediation;

import static com.auditsphere.remediation.Remediation.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Attachments of a corrective action, on the model of the control evidence of audit-service: the
 * object is stored first, then recorded under lock, and removed again if the record is refused.
 */
@RestController
@RequestMapping("/remediation/actions/{id}/attachments")
class AttachmentController {
  record Attachment(
      UUID id,
      String fileName,
      String contentType,
      long sizeBytes,
      String sha256,
      String uploadedBy,
      Instant uploadedAt) {}

  static final int MAX_BYTES = 10 * 1024 * 1024;
  static final int MAX_PER_ACTION = 10;
  private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);
  private static final String COLUMNS =
      "id, file_name, content_type, size_bytes, sha256, uploaded_by, uploaded_at";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Remediation remediation;
  private final AttachmentStore store;
  private final ChangeLog changes;
  private final long missionQuota;

  AttachmentController(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      Remediation remediation,
      AttachmentStore store,
      ChangeLog changes,
      @Value("${remediation.mission-quota-bytes}") long missionQuota) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(10);
    this.remediation = remediation;
    this.store = store;
    this.changes = changes;
    if (missionQuota < MAX_BYTES) throw new IllegalArgumentException("Attachment mission quota too small");
    this.missionQuota = missionQuota;
  }

  static List<Attachment> list(JdbcTemplate jdbc, UUID action) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM action_attachments WHERE action_id=? ORDER BY uploaded_at, id",
        AttachmentController::map,
        action);
  }

  private static Attachment map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Attachment(
        r.getObject("id", UUID.class),
        r.getString("file_name"),
        r.getString("content_type"),
        r.getLong("size_bytes"),
        r.getString("sha256"),
        r.getString("uploaded_by"),
        r.getTimestamp("uploaded_at").toInstant());
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Attachment> upload(
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam MultipartFile file,
      Authentication auth)
      throws Exception {
    var actor = Actor.of(auth);
    // Cheap checks before reading the file, repeated under lock below.
    transactions.executeWithoutResult(status -> writable(remediation.action(id, actor), actor, version));
    if (file.getSize() < 1 || file.getSize() > MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    String name = validName(file.getOriginalFilename());
    byte[] bytes;
    try (InputStream in = file.getInputStream()) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length < 1 || bytes.length > MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    String type = detectedType(bytes);
    if (!type.equals(file.getContentType())) throw invalid();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    UUID attachment = UUID.randomUUID();
    String key = "attachments/" + attachment;
    store.put(key, bytes, type);
    try {
      var saved =
          transactions.execute(
              status -> {
                var context = remediation.action(id, actor, true);
                writable(context, actor, version);
                if (jdbc.queryForObject(
                        "SELECT count(*) FROM action_attachments WHERE action_id=?", Integer.class, id)
                    >= MAX_PER_ACTION) throw conflict();
                long used =
                    jdbc.queryForObject(
                        "SELECT COALESCE(sum(size_bytes), 0) FROM action_attachments WHERE mission_id=?",
                        Long.class,
                        context.missionId());
                if (used > missionQuota - bytes.length)
                  throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
                jdbc.update(
                    """
                    INSERT INTO action_attachments(id, action_id, mission_id, object_key, file_name, content_type,
                      size_bytes, sha256, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    attachment,
                    id,
                    context.missionId(),
                    key,
                    name,
                    type,
                    bytes.length,
                    hash,
                    actor.subject());
                long next = bump(id, version, actor);
                changes.write(
                    context.missionId(),
                    context.followUpId(),
                    id,
                    actor.subject(),
                    "ATTACHMENT_ADDED",
                    next,
                    null,
                    false,
                    null,
                    Map.of("attachmentId", attachment, "sha256", hash, "sizeBytes", bytes.length));
                return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM action_attachments WHERE id=?",
                    AttachmentController::map,
                    attachment);
              });
      return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    } catch (RuntimeException refused) {
      try {
        store.delete(key);
      } catch (RuntimeException cleanup) {
        log.error("Unreferenced attachment object requires cleanup: {}", key, cleanup);
      }
      throw refused;
    }
  }

  @GetMapping("/{attachmentId}")
  ResponseEntity<StreamingResponseBody> download(
      @PathVariable UUID id, @PathVariable UUID attachmentId, Authentication auth) {
    var row =
        transactions.execute(
            status -> {
              remediation.readableAction(id, Actor.of(auth));
              var rows =
                  jdbc.queryForList(
                      "SELECT object_key, file_name, content_type, size_bytes, sha256 FROM action_attachments WHERE id=? AND action_id=?",
                      attachmentId,
                      id);
              if (rows.isEmpty()) throw notFound();
              return rows.getFirst();
            });
    String key = (String) row.get("object_key");
    String expectedHash = (String) row.get("sha256");
    long expectedSize = ((Number) row.get("size_bytes")).longValue();
    StreamingResponseBody body =
        output -> {
          try (InputStream in = store.get(key)) {
            MessageDigest digest;
            try {
              digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
              throw new IllegalStateException(e);
            }
            long total = 0;
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
              digest.update(buffer, 0, n);
              total += n;
              output.write(buffer, 0, n);
            }
            if (total != expectedSize
                || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) {
              log.error("Attachment integrity mismatch for object {}", key);
              throw new IOException("Attachment integrity mismatch");
            }
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) row.get("content_type")))
        .contentLength(expectedSize)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename((String) row.get("file_name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }

  /** Removal by its author, the owner of the action, until the action is declared done. */
  @DeleteMapping("/{attachmentId}")
  ResponseEntity<Void> remove(
      @PathVariable UUID id,
      @PathVariable UUID attachmentId,
      @RequestParam long version,
      Authentication auth) {
    var actor = Actor.of(auth);
    transactions.executeWithoutResult(
            status -> {
              var context = remediation.action(id, actor);
              writable(context, actor, version);
              var rows =
                  jdbc.queryForList(
                      "SELECT object_key, uploaded_by, sha256 FROM action_attachments WHERE id=? AND action_id=? FOR UPDATE",
                      attachmentId,
                      id);
              if (rows.isEmpty()) throw notFound();
              if (!actor.subject().equals(rows.getFirst().get("uploaded_by"))) throw forbidden();
              jdbc.update("DELETE FROM action_attachments WHERE id=?", attachmentId);
              long next = bump(id, version, actor);
              changes.write(
                  context.missionId(),
                  context.followUpId(),
                  id,
                  actor.subject(),
                  "ATTACHMENT_REMOVED",
                  next,
                  null,
                  false,
                  Map.of("attachmentId", attachmentId, "sha256", rows.getFirst().get("sha256")),
                  null);
              String objectKey = (String) rows.getFirst().get("object_key");
              // The object goes only once the row is gone for good.
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                      try {
                        store.delete(objectKey);
                      } catch (RuntimeException cleanup) {
                        log.error("Unreferenced attachment object requires cleanup: {}", objectKey, cleanup);
                      }
                    }
                  });
            });
    return ResponseEntity.noContent().build();
  }

  /** Only the owner of the action adds or removes attachments, while it is To do or In progress. */
  private static void writable(Remediation.Context context, Actor actor, long version) {
    if (!assignee(actor, context.assignee())) throw forbidden();
    requireWritable(context);
    if (!"ACTION_PLAN".equals(context.followUpStatus())
        || !Set.of("TODO", "IN_PROGRESS").contains(context.actionStatus())) throw conflict();
    requireVersion(context.action(), version);
  }

  private long bump(UUID action, long version, Actor actor) {
    jdbc.update(
        "UPDATE corrective_actions SET version=?, updated_by=?, updated_at=now() WHERE id=?",
        version + 1,
        actor.subject(),
        action);
    return version + 1;
  }

  private static String validName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.indexOf('/') >= 0
        || name.indexOf('\\') >= 0
        || name.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return name.strip();
  }

  /** Declared type must match the content: PDF, JPEG or PNG only. */
  static String detectedType(byte[] bytes) {
    if (bytes.length >= 5
        && bytes[0] == '%'
        && bytes[1] == 'P'
        && bytes[2] == 'D'
        && bytes[3] == 'F'
        && bytes[4] == '-') return "application/pdf";
    if (bytes.length >= 4
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255
        && (bytes[bytes.length - 2] & 255) == 255
        && (bytes[bytes.length - 1] & 255) == 217) return "image/jpeg";
    byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    if (bytes.length >= 20) {
      boolean match = true;
      for (int i = 0; i < png.length; i++) match &= bytes[i] == png[i];
      if (match) return "image/png";
    }
    throw invalid();
  }
}
