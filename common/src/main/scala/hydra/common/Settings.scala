package hydra.common

import com.typesafe.config.Config
import configs.syntax._
import hydra.common.config.ConfigSupport

import scala.concurrent.duration._

class Settings(config: Config) {
  val IngestTopicName: String = "hydra-ingest"

  val SchemaMetadataRefreshInterval = config
    .get[FiniteDuration]("schema.metadata.refresh.interval")
    .valueOrElse(1 minute)

  val httpPort = config.getInt("http.port")

  val httpInterface = config.getString("http.interface")
}

object Settings extends ConfigSupport {
  val HydraSettings = new Settings(applicationConfig)
}
