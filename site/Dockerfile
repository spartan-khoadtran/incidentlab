# The handbook. Build context is the repo root: the prebuild reads docs/, the Makefile,
# docker-compose.yml and the Gradle files, and the postbuild check reads Answers.kt.
FROM node:22.22-alpine AS build
WORKDIR /repo/site
COPY site/package.json site/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY docs /repo/docs
COPY Makefile docker-compose.yml README.md CLAUDE.md /repo/
COPY service /repo/service
COPY site /repo/site
RUN npm run build

FROM nginx:1.30.6-alpine
COPY site/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/site/dist /usr/share/nginx/html
