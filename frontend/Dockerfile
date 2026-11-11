# --- build stage ---
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- run stage ---
# 비루트(uid 101)로 8080에서 뜨는 nginx 이미지 — K8s runAsNonRoot와 Trivy 지적 대비
FROM nginxinc/nginx-unprivileged:1.27-alpine
# 포트를 환경변수로 받는다(nginx 이미지의 templates → envsubst). web VM은 host 네트워크라 backend(8080)와 겹치지 않게 8081을 준다
ENV NGINX_PORT=8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
