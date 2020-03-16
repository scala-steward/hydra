package hydra.ingest.app

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import hydra.common.Settings
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.ingest.bootstrap.{ActorFactory, RouteFactory}
import hydra.ingest.modules.{Algebras, Bootstrap, Programs}
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kamon.Kamon
import configs.syntax._
import kamon.prometheus.PrometheusReporter

import scala.concurrent.ExecutionContext.Implicits.global

// $COVERAGE-OFF$Disabling highlighting by default until a workaround for https://issues.scala-lang.org/browse/SI-8596 is found
object Main extends IOApp with ConfigSupport with LoggingAdapter {

  private implicit val catsLogger: SelfAwareStructuredLogger[IO] =
    Slf4jLogger.getLogger[IO]

  private def getActorSystem: Resource[IO, ActorSystem] = {
    val registerCoordinatedShutdown: ActorSystem => IO[Unit] = system =>
      IO(system.terminate())
    val system = IO(ActorSystem("hydra", rootConfig))
    Resource.make(system)(registerCoordinatedShutdown)
  }

  private def report =
    IO({
      val enablePrometheus = applicationConfig
        .get[Boolean]("monitoring.prometheus.enable")
        .valueOrElse(false)
      if (enablePrometheus) {
        val module = new PrometheusReporter()
        Kamon.registerModule("MainModule", module)
      }
    })

  private def actorsIO()(implicit system: ActorSystem): IO[Unit] = {
    IO {
      class Service extends Actor {
        override def preStart(): Unit = {
          ActorFactory.getActors().foreach {
            case (name, props) =>
              context.actorOf(props, name)
          }
        }
        override def receive: Receive = {
          case _ => ()
        }
      }
      system.actorOf(Props[Service], "service")
    }
  }

  private def routesIO()(implicit system: ActorSystem): IO[Route] =
    IO(RouteFactory.getRoutes())

  private def serverIO(routes: Route, settings: Settings)(
      implicit system: ActorSystem
  ): IO[ServerBinding] = {
    IO.fromFuture(
      IO(
        Http().bindAndHandle(routes, settings.httpInterface, settings.httpPort)
      )
    )
  }

  private def buildProgram()(implicit system: ActorSystem): IO[Unit] = {
    val ingestActorSelection = system.actorSelection(
      path = applicationConfig.getString("kafka-ingestor-path")
    )
    AppConfig.appConfig.load[IO].flatMap { config =>
      for {
        algebras <- Algebras
          .make[IO](config.createTopicConfig, ingestActorSelection)
        programs <- Programs.make[IO](config, algebras)
        bootstrap <- Bootstrap
          .make[IO](programs.createTopic, config.v2MetadataTopicConfig)
        _ <- actorsIO()
        _ <- bootstrap.bootstrapAll
        routes <- routesIO()
        _ <- report
        _ <- serverIO(routes, Settings.HydraSettings)
      } yield ()
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    getActorSystem.use { implicit system =>
      buildProgram() *> IO.never.map(_ => ExitCode.Success)
    }
  }
}

// $COVERAGE-ON
