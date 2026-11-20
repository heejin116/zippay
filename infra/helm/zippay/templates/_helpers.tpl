{{- define "zippay.labels" -}}
app.kubernetes.io/part-of: zippay
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "zippay.image" -}}
{{- $repo := required "image.repository가 필요하다 (--set image.repository=<Docker Hub 계정>)" .root.Values.image.repository -}}
{{- $tag := required "image.tag가 필요하다 (--set image.tag=<커밋 SHA>)" .root.Values.image.tag -}}
{{- if eq $tag "latest" }}{{ fail "image.tag에 latest를 쓰지 않는다 (커밋 SHA)" }}{{ end -}}
{{ $repo }}/zippay-{{ .app }}:{{ $tag }}
{{- end }}

{{/* 두 worker에 나눠 배치. 기본값(Ignore)이면 drain·장애로 taint가 붙은 노드도 개수에 넣어
     남은 노드에 다시 뜨지 못하므로 nodeTaintsPolicy: Honor로 그런 노드를 계산에서 뺀다 */}}
{{- define "zippay.spread" -}}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule
    nodeTaintsPolicy: Honor
    labelSelector:
      matchLabels:
        app.kubernetes.io/name: {{ . }}
{{- end }}

{{- define "zippay.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
{{- end }}
