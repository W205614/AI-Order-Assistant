FROM python:3.13-slim@sha256:881d80734ee05dca6f7f42dcb080975652a53c7eda9ba1f03bb8da31aa6a6ec2
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY evals ./evals
EXPOSE 8800
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]
