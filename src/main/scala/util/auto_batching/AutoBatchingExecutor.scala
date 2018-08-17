package util.auto_batching

import akka.actor.ActorSystem
import com.google.inject.Provider
import util.auto_batching.AutoBatchingExecutorDTOs.ConfigObject

import scala.concurrent.Future

trait AutoBatchingExecutor[I, R] {

  def resultById(id: I): Future[Option[R]]

}

/**
  * Implement this provider to use this library.
  * The library automatically collects all the requests for processing in a batch, and execute them in bulk with the
  * `processIds` implementation provided.
  * Creation of batch is done based on following condition:
  *   1. If for a configured amount of time (maxWaitTimeBetweenMessages), if it doesn't receive any other request to
  *      process.
  *   2. If by the time it is collecting requests, the max time for one batch (maxBatchInterval) exceeds.
  *   3. If number of ids to process exceeds maximum number of ids allowed in one batch (maxBatchSize).
  * @tparam I Id Type
  * @tparam R Result after processing the Ids
  */
trait AutoBatchingExecutorProvider[I, R] extends Provider[AutoBatchingExecutor[I, R]] {

  // Name of the actor
  def name: String

  def actorSystem: ActorSystem

  /**
    * Override this function, which will be called for processing ids in bulk collected in batch.
    * @param ids: List of IDs
    * @return Map of id and respective result after processing
    */
  def processIds(ids: List[I]): Future[Map[I, R]]

  /**
    * Warn time in milliseconds after which, It will check if the actor is still
    * processing the byId call, and if it is present, It will notify for Long processing time in the logs.
    */
  def warnAfterProcessingTime: Int = 100

  def configObject: ConfigObject

  override def get(): AutoBatchingExecutorImpl[I, R] =
    new AutoBatchingExecutorImpl[I, R](name, actorSystem, configObject, warnAfterProcessingTime, processIds)
}
