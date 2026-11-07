FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1
WORKDIR /app
# Solo librería estándar: no hay requirements.
COPY relay.py .
RUN useradd -r -u 10001 radar
USER radar
EXPOSE 8080
CMD ["python", "relay.py"]
