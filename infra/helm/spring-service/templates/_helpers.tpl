{{- define "spring-service.image" -}}
{{- $digest := required "image.digest est obligatoire" .Values.image.digest -}}
{{ required "image.repository est obligatoire" .Values.image.repository }}:{{ .Values.image.tag }}@{{ $digest }}
{{- end -}}

{{- define "spring-service.labels" -}}
app.kubernetes.io/name: {{ .Release.Name }}
app.kubernetes.io/part-of: auditsphere
{{- end -}}

{{- define "spring-service.podSecurity" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
# Les secrets montés en 0440 restent lisibles par le processus seulement.
fsGroup: 10001
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "spring-service.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end -}}

{{- define "spring-service.secretEnv" -}}
{{- range .Values.secretEnv }}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .key }}
{{- end }}
{{- end -}}
