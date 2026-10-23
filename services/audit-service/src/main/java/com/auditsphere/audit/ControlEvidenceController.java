package com.auditsphere.audit;

import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/missions/{missionId}/controls/{controlId}/evidence")
class ControlEvidenceController {
  private static final Logger log = LoggerFactory.getLogger(ControlEvidenceController.class);
  private static final int MAX_BYTES = 10 * 1024 * 1024;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final EvidenceStore store;
  private final long missionQuotaBytes;

  ControlEvidenceController(
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager manager,
      EvidenceStore store,
      @Value("${EVIDENCE_MISSION_QUOTA_BYTES:104857600}") long missionQuotaBytes) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(5);
    this.store = store;
    if (missionQuotaBytes < MAX_BYTES) throw new IllegalArgumentException("Evidence mission quota too small");
    this.missionQuotaBytes = missionQuotaBytes;
  }

  record Evidence(
      UUID id,
      String originalName,
      String mediaType,
      long byteSize,
      String sha256,
      String authorName,
      Instant createdAt) {}

  private boolean role(Jwt jwt, String role) {
    Map<String, Object> realm = jwt.getClaim("realm_access");
    return realm != null && realm.get("roles") instanceof List<?> roles && roles.contains(role);
  }

  private void access(
      UUID missionId, UUID controlId, Jwt jwt, boolean write, Long version, boolean lock) {
    String sql =
        "SELECT m.version,m.status,m.owner_subject,m.validator_subject,m.deleted_at,"
            + " EXISTS(SELECT 1 FROM mission_assignments a WHERE a.mission_id=m.id AND a.subject=?) assigned"
            + " FROM missions m JOIN mission_controls c ON c.mission_id=m.id AND c.id=?"
            + " WHERE m.id=?"
            + (lock ? " FOR UPDATE OF m" : "");
    var rows = jdbc.queryForList(sql, jwt.getSubject(), controlId, missionId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var row = rows.getFirst();
    boolean assigned = Boolean.TRUE.equals(row.get("assigned"));
    boolean admin = role(jwt, "SUPER_ADMIN");
    if (row.get("deleted_at") != null
        || !(admin
            || jwt.getSubject().equals(row.get("owner_subject"))
            || assigned
            || (role(jwt, "AUDIT_VALIDATOR")
                && jwt.getSubject().equals(row.get("validator_subject")))))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (write) {
      if (!(admin || (assigned && role(jwt, "AUDITOR"))))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
      if (!"IN_PROGRESS".equals(row.get("status"))
          || version == null
          || ((Number) row.get("version")).longValue() != version)
        throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
  }

  private String validName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.indexOf('/') >= 0
        || name.indexOf('\\') >= 0
        || name.chars().anyMatch(c -> c < 32 || c == 127))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return name.strip();
  }

  private String detectedType(byte[] bytes) {
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
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  private Evidence map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Evidence(
        r.getObject("id", UUID.class),
        r.getString("original_name"),
        r.getString("media_type"),
        r.getLong("byte_size"),
        r.getString("sha256"),
        r.getString("author_name"),
        r.getTimestamp("created_at").toInstant());
  }

  @GetMapping
  List<Evidence> list(
      @PathVariable UUID missionId,
      @PathVariable UUID controlId,
      @AuthenticationPrincipal Jwt jwt) {
    access(missionId, controlId, jwt, false, null, false);
    return jdbc.query(
        "SELECT id,original_name,media_type,byte_size,sha256,author_name,created_at"
            + " FROM control_evidence WHERE mission_id=? AND control_id=? ORDER BY created_at,id",
        this::map,
        missionId,
        controlId);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Evidence> upload(
      @PathVariable UUID missionId,
      @PathVariable UUID controlId,
      @RequestParam long version,
      @RequestParam MultipartFile file,
      @AuthenticationPrincipal Jwt jwt)
      throws Exception {
    access(missionId, controlId, jwt, true, version, false);
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
    if (!type.equals(file.getContentType()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    UUID id = UUID.randomUUID();
    String key = "evidence/" + id;
    store.put(key, bytes, type);
    try {
      Evidence saved =
          transactions.execute(
              status -> {
                access(missionId, controlId, jwt, true, version, true);
                Integer count =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM control_evidence WHERE mission_id=? AND control_id=?",
                        Integer.class,
                        missionId,
                        controlId);
                if (count != null && count >= 100)
                  throw new ResponseStatusException(HttpStatus.CONFLICT);
                Long used = jdbc.queryForObject(
                    "SELECT COALESCE(sum(byte_size),0) FROM control_evidence WHERE mission_id=?",
                    Long.class, missionId);
                if (used == null || used > missionQuotaBytes - bytes.length)
                  throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
                String author = jwt.getClaimAsString("name");
                if (author == null || author.isBlank())
                  author = jwt.getClaimAsString("preferred_username");
                if (author == null || author.isBlank()) author = jwt.getSubject();
                author = author.length() > 255 ? author.substring(0, 255) : author;
                jdbc.update(
                    "INSERT INTO control_evidence(id,mission_id,control_id,object_key,original_name,"
                        + "media_type,byte_size,sha256,author_subject,author_name) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id,
                    missionId,
                    controlId,
                    key,
                    name,
                    type,
                    bytes.length,
                    hash,
                    jwt.getSubject(),
                    author);
                jdbc.update(
                    "UPDATE missions SET version=version+1,program_revision=? WHERE id=?",
                    UUID.randomUUID(),
                    missionId);
                return jdbc.queryForObject(
                    "SELECT id,original_name,media_type,byte_size,sha256,author_name,created_at"
                        + " FROM control_evidence WHERE id=?",
                    this::map,
                    id);
              });
      return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    } catch (RuntimeException failure) {
      try {
        store.delete(key);
      } catch (RuntimeException cleanup) {
        log.error("Unreferenced evidence object requires cleanup: {}", key, cleanup);
      }
      throw failure;
    }
  }

  @GetMapping("/{evidenceId}")
  ResponseEntity<StreamingResponseBody> download(
      @PathVariable UUID missionId,
      @PathVariable UUID controlId,
      @PathVariable UUID evidenceId,
      @AuthenticationPrincipal Jwt jwt) {
    access(missionId, controlId, jwt, false, null, false);
    var rows =
        jdbc.queryForList(
            "SELECT object_key,original_name,media_type,byte_size,sha256"
                + " FROM control_evidence WHERE id=? AND mission_id=? AND control_id=?",
            evidenceId,
            missionId,
            controlId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var row = rows.getFirst();
    String key = (String) row.get("object_key");
    String expectedHash = (String) row.get("sha256");
    long expectedSize = ((Number) row.get("byte_size")).longValue();
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
            if (total != expectedSize || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) {
              log.error("Evidence integrity mismatch for object {}", key);
              throw new IOException("Evidence integrity mismatch");
            }
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) row.get("media_type")))
        .contentLength(((Number) row.get("byte_size")).longValue())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    (String) row.get("original_name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }
}
