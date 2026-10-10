ARG NODE_IMAGE
ARG NGINX_IMAGE
FROM ${NODE_IMAGE} AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM ${NGINX_IMAGE}
COPY --from=build /build/dist/ /usr/share/nginx/html/
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
