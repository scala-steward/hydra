package hydra.ingest.services

import cats.effect.{Concurrent, ContextShift, IO}
import cats.implicits._
import hydra.avro.registry.SchemaRegistry
import hydra.core.transport.ValidationStrategy
import hydra.ingest.services.IngestionFlowV2.V2IngestRequest
import hydra.kafka.algebras.KafkaClientAlgebra
import hydra.kafka.model.TopicMetadataV2Request.Subject
import org.apache.avro.{Schema, SchemaBuilder}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

final class IngestionFlowV2Spec extends AnyFlatSpec with Matchers {

  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val concurrentEffect: Concurrent[IO] = IO.ioConcurrentEffect
  private implicit val mode: scalacache.Mode[IO] = scalacache.CatsEffect.modes.async

  private val testSubject: Subject = Subject.createValidated("exp.test.v0.Testing").get

  private val testKeyPayload: String =
    """{"id": "testing"}"""

  private val testValPayload: String =
    s"""{"testField": true}"""

  private val testKeySchema: Schema = SchemaBuilder.record("TestRecord")
    .fields().requiredString("id").endRecord()

  private val testValSchema: Schema = SchemaBuilder.record("TestRecord")
    .fields().requiredBoolean("testField").endRecord()

  private def ingest(request: V2IngestRequest): IO[KafkaClientAlgebra[IO]] = for {
    schemaRegistry <- SchemaRegistry.test[IO]
    _ <- schemaRegistry.registerSchema(testSubject.value + "-key", testKeySchema)
    _ <- schemaRegistry.registerSchema(testSubject.value + "-value", testValSchema)
    kafkaClient <- KafkaClientAlgebra.test[IO]
    ingestFlow <- IO(new IngestionFlowV2[IO](schemaRegistry, kafkaClient, "https://schemaRegistry.notreal"))
    _ <- ingestFlow.ingest(request)
  } yield kafkaClient

  it should "ingest a record" in {
    val testRequest = V2IngestRequest(testSubject, testKeyPayload, testValPayload.some, ValidationStrategy.Strict)
    ingest(testRequest).flatMap { kafkaClient =>
      kafkaClient.consumeMessages(testSubject.value, "test-consumer").take(1).compile.toList.map { publishedMessages =>
        val firstMessage = publishedMessages.head
        (firstMessage._1.toString, firstMessage._2.get.toString) shouldBe (testKeyPayload, testValPayload)
      }
    }.unsafeRunSync()
  }

  it should "ingest a tombstone record" in {
    val testRequest = V2IngestRequest(testSubject, testKeyPayload, None, ValidationStrategy.Strict)
    ingest(testRequest).flatMap { kafkaClient =>
      kafkaClient.consumeMessages(testSubject.value, "test-consumer").take(1).compile.toList.map { publishedMessages =>
        val firstMessage = publishedMessages.head
        (firstMessage._1.toString, firstMessage._2) shouldBe (testKeyPayload, None)
      }
    }.unsafeRunSync()
  }

  it should "ingest a record with extra fields and Relaxed validation" in {
    val testKeyPayloadAlt: String = """{"id": "testing", "random": "blah"}"""
    val testValPayloadAlt: String =s"""{"testField": true, "other": 1000}"""

    val testRequest = V2IngestRequest(testSubject, testKeyPayloadAlt, testValPayloadAlt.some, ValidationStrategy.Relaxed)
    ingest(testRequest).flatMap { kafkaClient =>
      kafkaClient.consumeMessages(testSubject.value, "test-consumer").take(1).compile.toList.map { publishedMessages =>
        val firstMessage = publishedMessages.head
        (firstMessage._1.toString, firstMessage._2.get.toString) shouldBe (testKeyPayload, testValPayload)
      }
    }.unsafeRunSync()
  }

  it should "reject a record with extra fields and Strict validation" in {
    val testKeyPayloadAlt: String = """{"id": "testing", "random": "blah"}"""
    val testValPayloadAlt: String =s"""{"testField": true, "other": 1000}"""

    val testRequest = V2IngestRequest(testSubject, testKeyPayloadAlt, testValPayloadAlt.some, ValidationStrategy.Strict)
    ingest(testRequest).attempt.unsafeRunSync() shouldBe a[Left[_, _]]
  }

  it should "reject a record that doesn't match schema" in {
    val testKeyPayloadAlt: String = """{"id": "testing"}"""
    val testValPayloadAlt: String =s"""{"testFieldOther": 1000}"""

    val testRequest = V2IngestRequest(testSubject, testKeyPayloadAlt, testValPayloadAlt.some, ValidationStrategy.Strict)
    ingest(testRequest).attempt.unsafeRunSync() shouldBe a[Left[_, _]]
  }

}
