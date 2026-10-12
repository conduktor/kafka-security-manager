# Try Kafka permissions with the Docker demo

From the repository root, start the published KSM image and a disposable Kafka broker:

```sh
docker compose up --no-build -d
docker compose logs -f ksm
```

Wait for `Reconciliation succeeded`, then press Ctrl-C to leave the logs. The [demo policy](../example/demo-acls.csv) grants the `app` identity access to the `orders` topic and `orders-reader` consumer group. Send a message using that identity:

```sh
printf 'hello from KSM\n' | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic orders \
  --producer.config /demo/demo-app.properties

docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic orders --group orders-reader \
  --consumer.config /demo/demo-app.properties --from-beginning \
  --max-messages 1 --timeout-ms 15000
```

You should see `hello from KSM`. Stop the demo and remove its storage when done:

```sh
docker compose down -v
```

The host listener binds to `127.0.0.1:19092`; set `KSM_DEMO_PORT` if that port is busy. The checked-in credentials are for this local demo only.

To try a locally changed KSM, build it with Java 25, then select a local image name:

```sh
./mvnw verify
KSM_IMAGE=ksm-development:local docker compose up --build -d
```
