# Holdout game server for fly.io (or any Docker host).
# Build stage: install, bundle the game page and the camp worker.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN node server/build.mjs

# Run stage: just the server, the built page, the worker and ws.
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data
COPY --from=build /app/server/server.mjs ./server/server.mjs
COPY --from=build /app/server/public ./server/public
COPY --from=build /app/server/dist ./server/dist
COPY --from=build /app/node_modules/ws ./node_modules/ws
RUN echo '{"type":"module"}' > package.json && mkdir -p /data
EXPOSE 8080
CMD ["node", "server/server.mjs"]
