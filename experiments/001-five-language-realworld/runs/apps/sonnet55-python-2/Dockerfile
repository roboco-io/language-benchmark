FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8000 \
    DATABASE_PATH=/data/realworld.db

WORKDIR /srv
COPY requirements.txt .
RUN pip install --disable-pip-version-check -r requirements.txt

COPY app ./app
COPY run.py .

# Schema is created at startup (run.py), so an empty /data volume works.
RUN mkdir -p /data
VOLUME /data
EXPOSE 8000

CMD ["python", "run.py"]
