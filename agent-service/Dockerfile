FROM python:3.13-slim@sha256:881d80734ee05dca6f7f42dcb080975652a53c7eda9ba1f03bb8da31aa6a6ec2
WORKDIR /app
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/* \
    && apt-get -o Acquire::Retries=5 update && apt-get -o Acquire::Retries=5 upgrade -y && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
COPY requirements.lock.txt .
RUN pip install --no-cache-dir -r requirements.txt -r requirements.lock.txt && pip check
# Installers and their vendored dependencies are unnecessary in a read-only runtime.
RUN python -m pip uninstall -y pip && rm -rf /usr/local/lib/python3.13/ensurepip
COPY app ./app
COPY evals ./evals
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app && mkdir -p /app/metrics && chown -R app:app /app
USER 10001:10001
EXPOSE 8800
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]
