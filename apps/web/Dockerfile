# Build context: apps/web. Runtime settings and secrets come from the environment, never from the image.
FROM node:24.22.1-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts

FROM node:24.22.1-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY . .
# Security headers are computed at build time: only the HTTPS scheme is read, to emit HSTS.
ARG APP_ORIGIN=https://auditsphere.invalid
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24.22.1-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
# The server never runs npm: dropping it removes its bundled dependencies from the attack surface.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static .next/static
USER 10001:10001
EXPOSE 3000
CMD ["node", "server.js"]
