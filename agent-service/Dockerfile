FROM python:3.15-alpine3.24@sha256:20a82dbc2783112ddb91d1534603f802d222a3e63e593cd002eb2ac534e70211
WORKDIR /app
RUN apk upgrade --no-cache
COPY requirements.txt .
COPY requirements.lock.txt .
RUN pip install --no-cache-dir -r requirements.txt -r requirements.lock.txt && pip check
# Installers and their vendored dependencies are unnecessary in a read-only runtime.
RUN python -m pip uninstall -y pip && rm -rf /usr/local/lib/python3.13/ensurepip
COPY app ./app
COPY evals ./evals
RUN addgroup -g 10001 app && adduser -D -H -u 10001 -G app app \
    && mkdir -p /app/metrics && chown -R app:app /app
USER 10001:10001
EXPOSE 8800
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]
