FROM python:3.11-slim

# pdfplumber(pdfminer.six)가 CJK 폰트 메트릭을 다룰 때 필요한 최소 런타임
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml ./
# torch는 CPU 휠로 받는다. 기본 인덱스의 CUDA 빌드는 2GB 이상 크고,
# 이 이미지가 GPU를 쓸 일은 없다(GPU 추론은 Ollama 컨테이너 담당).
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir ".[embeddings]"

COPY app ./app

# 임베딩 모델을 빌드 시점에 미리 받아둔다. 첫 요청이 2.3GB 다운로드를 기다리면
# 헬스체크가 먼저 죽는다.
ARG EMBEDDING_MODEL=BAAI/bge-m3
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${EMBEDDING_MODEL}')"

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
