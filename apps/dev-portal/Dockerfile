# Next.js 앱 이미지 — pnpm 워크스페이스 전체를 받아 해당 앱만 빌드한다.
# 빌드 컨텍스트는 레포 루트다 (compose의 context: . / dockerfile: apps/<app>/Dockerfile).
ARG APP_NAME

FROM node:24-alpine AS deps
RUN corepack enable
WORKDIR /repo
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY packages ./packages
COPY apps ./apps
# 워크스페이스 링크가 필요하므로 전체를 설치한다. lockfile 고정.
RUN pnpm install --frozen-lockfile

FROM deps AS build
ARG APP_NAME
# next.config의 rewrites는 빌드 시점에 routes-manifest.json으로 굳는다 —
# 런타임 환경변수로는 바뀌지 않으므로 빌드 인자로 넣어야 한다.
ARG FRACTA_API_ORIGIN=http://app:8080
ENV FRACTA_API_ORIGIN=${FRACTA_API_ORIGIN}
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter "$APP_NAME" build

FROM node:24-alpine AS runtime
ARG APP_NAME
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
RUN addgroup -S fracta && adduser -S fracta -G fracta
USER fracta

# standalone 산출물은 필요한 node_modules까지 함께 들고 나온다
COPY --from=build --chown=fracta:fracta /repo/apps/${APP_NAME}/.next/standalone ./
COPY --from=build --chown=fracta:fracta /repo/apps/${APP_NAME}/.next/static ./apps/${APP_NAME}/.next/static

ENV APP_PATH=apps/${APP_NAME}/server.js
EXPOSE 3000
CMD ["sh", "-c", "node $APP_PATH"]
