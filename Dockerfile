FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

# torch só CPU: a roda padrão do PyPI no Linux traz CUDA (vários GB) e o cluster não tem GPU
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN useradd --create-home app
USER app
ENV HF_HOME=/home/app/.cache/huggingface

# baixa o CLIP durante o build: o pod sobe sem depender do Hugging Face
ARG MODELO_CLIP=openai/clip-vit-base-patch32
ENV MODELO_CLIP=${MODELO_CLIP}
RUN python -c "from transformers import pipeline; pipeline('zero-shot-image-classification', model='${MODELO_CLIP}')"
ENV HF_HUB_OFFLINE=1

COPY --chown=app src ./src

EXPOSE 8000
CMD ["uvicorn", "src.api.app:criar_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
