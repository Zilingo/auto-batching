package util.auto_batching

import play.api.libs.json.{Json, Reads}

object AutoBatchingExecutorDTOs {

  /**
    *
    * @param maxWaitTimeBetweenMessages: The maximum time in milliseconds the actor will wait for any message to come,
    *                                  If it does not find any message,  It will execute the call. This is done so that
    *                                  if only 1 message has to be processed , It wont wait till the maximum time
    *                                  allotted for one batch.
    *
    * @param thresholdBatchSizeForProcessing: Threshold size of the batch after which the processing unit (actor) will
    *                                       requests for ceasing further requests for processing. This is not strict
    *                                       batch size for processing, as processing unit (actor) can include more
    *                                       requests received before ceasing requests acknowledgment
    *
    * @param maxBatchInterval: The maximum time in milliseconds it will wait for one particular batch of messages,
    *                        After this time, It will automatically start processing the batch even if
    *                        Max batch size has not yet been reached.
    *
    * @param requestTimeout: Timeout in milliseconds, after which the parent actor will exit without any result.
    */
  case class ConfigObject(maxWaitTimeBetweenMessages: Int = 1, thresholdBatchSizeForProcessing: Int = 100,
                          maxBatchInterval: Int = 10, requestTimeout: Int = 60000)
  object ConfigObject {
    implicit val reads: Reads[ConfigObject] = Json.reads[ConfigObject]
  }

}
