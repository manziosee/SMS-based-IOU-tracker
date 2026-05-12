# ---- Stage 1: Build uberjar ----
FROM clojure:lein-2.11.2-alpine AS builder

WORKDIR /app

# Cache dependencies before copying source
COPY project.clj .
RUN lein deps

COPY . .
RUN lein uberjar

# ---- Stage 2: Minimal runtime image ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S iou && adduser -S iou -G iou
USER iou

COPY --from=builder /app/target/uberjar/sms-iou-tracker-0.1.0-SNAPSHOT-standalone.jar app.jar

# SQLite DB lives in a volume-mounted directory
VOLUME /data

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:3000/ || exit 1

ENV PORT=3000
ENV DB_PATH=/data/iou_tracker.db

ENTRYPOINT ["java", "-jar", "app.jar"]
