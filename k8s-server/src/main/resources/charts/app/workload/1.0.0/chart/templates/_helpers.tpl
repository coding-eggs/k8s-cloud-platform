{{- define "app.name" -}}
{{ .Values.app.slug }}-{{ .Values.workload.key }}
{{- end -}}

{{- define "app.labels" -}}
app.kubernetes.io/name: {{ .Values.app.slug | quote }}
app.kubernetes.io/managed-by: k8s-cloud-platform
platform.coding/app: {{ .Values.app.id | quote }}
platform.coding/app-node: {{ .Values.workload.key | quote }}
platform.coding/managed: "true"
{{- end -}}

{{- define "app.selector" -}}
platform.coding/app: {{ .Values.app.id | quote }}
platform.coding/app-node: {{ .Values.workload.key | quote }}
{{- end -}}

{{- define "app.revision" -}}
platform.coding/revision: {{ .Values.app.revision | quote }}
{{- end -}}
