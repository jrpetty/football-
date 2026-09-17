# -----------------------------------------------------------------------------
# Tally, as a website.
#
# Two stages: one that builds the app, one that serves it. The serving image
# carries no toolchain and no source — the built site and a dependency-free
# server, which is why there is so little in it to go wrong.
# -----------------------------------------------------------------------------

FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first, so a change to the app does not reinstall the world.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080
COPY serve.mjs ./
COPY --from=build /app/dist ./dist
# Nothing here needs root.
USER node
EXPOSE 8080
CMD ["node", "serve.mjs"]
