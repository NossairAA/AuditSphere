{{- define "demo-reset.keycloak" -}}
- name: KEYCLOAK_URL
  value: http://keycloak.auditsphere.svc.cluster.local:8080
- name: DEMO_RESET_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: keycloak-demo-reset
      key: client_secret
- name: PYTHONDONTWRITEBYTECODE
  value: "1"
{{- end -}}

{{- define "demo-reset.pythonMounts" -}}
- name: scripts
  mountPath: /scripts
- name: work
  mountPath: /work
- name: accounts
  mountPath: /etc/auditsphere/demo
  readOnly: true
{{- end -}}

{{- define "demo-reset.resources" -}}
requests:
  cpu: 20m
  memory: 32Mi
limits:
  memory: 128Mi
{{- end -}}

{{- define "demo-reset.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end -}}
