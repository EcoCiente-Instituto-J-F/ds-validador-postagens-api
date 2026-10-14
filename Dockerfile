# Build do jar com Java 21 (o cache das dependências fica numa camada própria)
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew dependencies --no-daemon > /dev/null

COPY src ./src

RUN ./gradlew clean bootJar -x test --no-daemon \
    && JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" app.jar


# Baixa os arquivos do CLIP durante o build: o pod sobe sem depender do Hugging Face.
# Os tamanhos são conferidos para pegar download truncado (um arquivo parcial não passa do build).
FROM alpine:3.20 AS modelo

ARG CLIP_REVISAO=main
ARG BASE_URL=https://huggingface.co/Xenova/clip-vit-base-patch32/resolve

RUN apk add --no-cache curl

RUN mkdir -p /modelos/clip-vit-base-patch32 && cd /modelos/clip-vit-base-patch32 \
    && curl -fsSL --retry 5 -o vision_model.onnx "${BASE_URL}/${CLIP_REVISAO}/onnx/vision_model.onnx" \
    && curl -fsSL --retry 5 -o text_model.onnx "${BASE_URL}/${CLIP_REVISAO}/onnx/text_model.onnx" \
    && curl -fsSL --retry 5 -o tokenizer.json "${BASE_URL}/${CLIP_REVISAO}/tokenizer.json" \
    && test "$(stat -c%s vision_model.onnx)" -eq 351685709 \
    && test "$(stat -c%s text_model.onnx)" -eq 254058553 \
    && test "$(stat -c%s tokenizer.json)" -gt 2000000


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /app/app.jar app.jar
COPY --from=modelo --chown=app:app /modelos /app/modelos

USER app:app

ENV CLIP_DIR=/app/modelos/clip-vit-base-patch32 \
    SERVER_PORT=8000 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
