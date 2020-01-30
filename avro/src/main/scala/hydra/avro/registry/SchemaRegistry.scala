package hydra.avro.registry

import cats.effect.Sync
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, MockSchemaRegistryClient, SchemaRegistryClient}
import org.apache.avro.Schema

trait SchemaRegistry[F[_]] {

  import SchemaRegistry._

  def registerSchema(subject: String, schema: Schema): F[SchemaId]

  def deleteSchemaOfVersion(subject: String, version: SchemaVersion): F[Unit]

  def getVersion(subject: String, schema: Schema): F[SchemaVersion]

}

object SchemaRegistry {

  type SchemaId = Int

  sealed abstract class SchemaVersion {
    def getStringVersion: String = this match {
      case NumericSchemaVersion(v) => v.toString
      case LatestSchemaVersion => "latest"
    }
  }
  object SchemaVersion {
    def create[A](input: A): Option[SchemaVersion] = input match {
      case "latest" => Some(LatestSchemaVersion)
      case i: Int => Some(NumericSchemaVersion(i))
      case _ => None
    }
  }
  final case class NumericSchemaVersion(value: Int) extends SchemaVersion
  case object LatestSchemaVersion extends SchemaVersion

  def live[F[_]](schemaRegistryBaseUrl: String, maxCacheSize: Int)
                (implicit F: Sync[F]): F[SchemaRegistry[F]] = Sync[F].delay {
    getFromSchemaRegistryClient(new CachedSchemaRegistryClient(schemaRegistryBaseUrl, maxCacheSize))
  }

  def test[F[_]](implicit F: Sync[F]): F[SchemaRegistry[F]] = Sync[F].delay {
    getFromSchemaRegistryClient(new MockSchemaRegistryClient)
  }

  private[this] def getFromSchemaRegistryClient[F[_]](schemaRegistryClient: SchemaRegistryClient)
                                                     (implicit F: Sync[F]): SchemaRegistry[F] = new SchemaRegistry[F] {

    override def registerSchema(subject: String, schema: Schema): F[SchemaId] =
      Sync[F].delay(schemaRegistryClient.register(subject, schema))

    override def deleteSchemaOfVersion(subject: String, version: SchemaVersion): F[Unit] =
      Sync[F].delay(schemaRegistryClient.deleteSchemaVersion(subject, version.getStringVersion))

    override def getVersion(subject: String, schema: Schema): F[SchemaVersion] =
      Sync[F].delay {
        val version = schemaRegistryClient.getVersion(subject, schema)
        SchemaVersion.create(version)
      }
  }

}
