package hydra.ingest.modules

import akka.actor.ActorSelection
import cats.effect.{Async, Concurrent, ContextShift}
import cats.implicits._
import hydra.avro.registry.SchemaRegistry
import hydra.ingest.app.AppConfig.{CreateTopicConfig, SchemaRegistryConfig}
import hydra.kafka.util.KafkaClient

final class Algebras[F[_]] private (
    val schemaRegistry: SchemaRegistry[F],
    val kafkaClient: KafkaClient[F]
)

object Algebras {

  def make[F[_]: Async: Concurrent: ContextShift](
      createTopicConfig: CreateTopicConfig,
      ingestActorSelection: ActorSelection
  ): F[Algebras[F]] =
    for {
      schemaRegistry <- SchemaRegistry.live[F](
        createTopicConfig.schemaRegistryConfig.fullUrl,
        createTopicConfig.schemaRegistryConfig.maxCacheSize
      )
      kafkaClient <- KafkaClient.live[F](
        createTopicConfig.bootstrapServers,
        ingestActorSelection
      )
    } yield new Algebras[F](schemaRegistry, kafkaClient)
}