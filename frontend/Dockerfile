FROM node:24-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci --no-fund --no-audit
COPY . .
RUN npm run build
FROM nginxinc/nginx-unprivileged:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/notify/browser /usr/share/nginx/html
EXPOSE 8080
