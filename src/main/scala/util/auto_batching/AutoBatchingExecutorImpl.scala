package util.auto_batching

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import org.joda.time.DateTime
import util.auto_batching.AutoBatchingExecutorDTOs.ConfigObject

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}


private[auto_batching] class AutoBatchingExecutorImpl[I, R](name: String,
                                                            actorSystem: ActorSystem,
                                                            configObject: ConfigObject,
                                                            warnAfterProcessingTime: Int,
                                                            processIds: List[I] => Future[Map[I, R]])
  extends AutoBatchingExecutor[I, R] {

  private implicit val timeout: Timeout = Timeout(configObject.requestTimeout.milliseconds)

  private val parentActorRef = actorSystem.actorOf(RetrieveForIdsInBatchActor.props, name)

  override def resultById(id: I): Future[Option[R]] = {
    (parentActorRef ? RetrieveForIdsInBatchActor.RetrieveResultForId(id)).map {
      case response: RetrievedResultStatus => response match {
        case FoundResult(res) => Some(res)
        case NotFound => None
        case ProcessingFailed(t) =>
          throw new Exception(s"[RetrieveResultsForIdsInBatchChildActor] Retrieval of result failed for $id", t)
      }
      case _ => throw new Exception(s"[RetrieveResultsForIdsInBatchChildActor] Unhandled response sent by processing actor")
    }
  }

  /** Primary actor which is always listening to requests to fetch result for Ids
    * It uses [RetrieveResultsForIdsInBatchChildActor] as child actor and mainly listen to following messages:
    *   [RetrieveResultForId] to collect the Ids based on the capacity
    *   [RequestingForProcessing] sent by child actor when capacity is reached for child actor, so parent sends further
    *     messages to other child actor and watch to the current child actor until its active in case if it is stuck or
    *     processing requests is inefficient so that it can warn for slow processing as well
    */
  private class RetrieveForIdsInBatchActor extends Actor {

    import RetrieveForIdsInBatchActor._
    import RetrieveForIdsInBatchChildActor._

    object BatchProcessingChildActor {

      private var childActorOpt: Option[ActorRef] = None
      private val activeChildrenStatusCheck = mutable.HashMap.empty[String, (Cancellable, DateTime)]

      private def scheduleStatusCheck(actor: ActorRef) = {
        activeChildrenStatusCheck += actor.path.name -> ((
          context.system.scheduler.
            scheduleOnce(warnAfterProcessingTime.milliseconds, self, ChildInProcessingState(actor)),
          watchingChildActorSinceOpt(actor).getOrElse(DateTime.now)
        ))
      }

      private def watchingChildActorSinceOpt(actor: ActorRef) = activeChildrenStatusCheck.get(actor.path.name).map(_._2)

      def getOrCreateChildActor = childActorOpt.getOrElse {
        val childActor = context.actorOf(RetrieveForIdsInBatchChildActor.props)
        childActorOpt = Some(childActor)
        childActor
      }

      def markRequestedChildActorInactive(actor: ActorRef) = {
        scheduleStatusCheck(actor)
        context.watch(actor)
        childActorOpt = None
      }

      def warnForInefficientProcessing(childActor: ActorRef,
                                       parentActor: ActorRef) = watchingChildActorSinceOpt(childActor).foreach { waitingSince =>
        val elapsedTime = (DateTime.now.getMillis - waitingSince.getMillis).milliseconds
        logger.warn(s"[RetrieveForIdsInBatchActor] Processing time elapsed for parent actor : ${parentActor.path.name}, " +
          s"child actor ${childActor.path.name}: $elapsedTime")
        scheduleStatusCheck(childActor)
      }

      def finished(child: ActorRef) = {
        val key = child.path.name
        activeChildrenStatusCheck.get(key).map(_._1.cancel())
        activeChildrenStatusCheck -= key
      }
    }

    override def receive: Receive = {
      case RetrieveResultForId(id) =>
        BatchProcessingChildActor.getOrCreateChildActor.forward(CollectForBatchProcessing(id))

      case RequestingForProcessing =>
        sender() ! ProcessingRequestGranted
        BatchProcessingChildActor.markRequestedChildActorInactive(sender())

      case ChildInProcessingState(child) => BatchProcessingChildActor.warnForInefficientProcessing(child, self)

      case Terminated(child) => BatchProcessingChildActor.finished(child)
    }
  }

  private object RetrieveForIdsInBatchActor {
    case object RequestingForProcessing
    case class RetrieveResultForId(id: I)
    case class ChildInProcessingState(child: ActorRef)

    def props = Props(new RetrieveForIdsInBatchActor)
  }


  /** Processor actor which receives forwarded messages and if one of the following situation is reached:
    *   1. If for configured maxWaitTimeBetweenMessages it doesn't get any other request for processing id
    *   2. If by the time it is collecting id is exceeding configured maxBatchTime
    *   3. If number of ids to process is exceeding configured maxBatchSize
    * Once creation of batch is done this child actor send processing request to parent (at max one request will be sent),
    * parent acknowledges request and grant it and then child retrieves result for collected batch of request based on
    * Ids and send back response to respective sender (who requested for processing)
    */

  private class RetrieveResultsForIdsInBatchChildActor extends Actor {

    import RetrieveForIdsInBatchActor._
    import RetrieveForIdsInBatchChildActor._

    val idsToSenders = mutable.HashMap.empty[I, List[ActorRef]]
    case class RetrievedResults(result: Either[Throwable, Map[I, R]])

    object RequestParentActor {

      private var processingRequestSent = false

      private val maxBatchTime = scheduleRequestForProcessing(configObject.maxBatchInterval.milliseconds)

      private var maxWaitTimeOpt: Option[Cancellable] = None

      def startOrScheduleProcessing() = if (!processingRequestSent) {
        maxWaitTimeOpt.foreach(_.cancel())
        maxWaitTimeOpt = None
        if (idsToSenders.size >= configObject.thresholdBatchSizeForProcessing) {
          self ! RequestParentForProcessing
        } else {
          maxWaitTimeOpt = Some(scheduleRequestForProcessing(configObject.maxWaitTimeBetweenMessages.milliseconds))
        }
      }

      /** Here check with variable *processingRequestSent* is required because of case when global termination schedule
        * is just executed and at the same time shouldWaitMore() function was executing so there will be 2 messages in
        * mailbox for requestForProcessing and hence to send only one message to parent, check is added over here
        */
      def requestParentForProcessingOnce(f: => Unit) = if (!processingRequestSent) {
        Seq(Some(maxBatchTime), maxWaitTimeOpt).flatten.foreach(_.cancel())
        processingRequestSent = true
        f
      }

      private def scheduleRequestForProcessing(time: FiniteDuration) =
        context.system.scheduler.scheduleOnce(time, self, RequestParentForProcessing)
    }

    override def receive: Receive = {

      case CollectForBatchProcessing(id) =>
        RequestParentActor.startOrScheduleProcessing()
        val finalSenderList = sender() :: idsToSenders.getOrElse(id, Nil)
        idsToSenders += id -> finalSenderList

      case RequestParentForProcessing => RequestParentActor.requestParentForProcessingOnce {
        context.parent ! RequestingForProcessing
      }

      case ProcessingRequestGranted =>
        safely(processIds(idsToSenders.keys.toList))
          .map(self ! RetrievedResults(_))

      case RetrievedResults(result) =>
        def flush(toMessage: I => RetrievedResultStatus): Unit =
          idsToSenders.foreach { case (id, senders) =>
            val message = toMessage(id)
            senders.foreach(_ ! message)
          }

        result match {
          case Left(t) => flush(_ => ProcessingFailed(t))
          case Right(res) => flush(res.get(_) match {
            case Some(succeeded) => FoundResult(succeeded)
            case None => NotFound
          })
        }
        self ! PoisonPill
    }
  }

  private def safely[F](f: => Future[F]): Future[Either[Throwable, F]] =
    Try(f.map(Right(_)).recover { case t => Left(t) }) match {
      case Failure(exception) => Future.successful(Left(exception))
      case Success(value) => value
    }

  private object RetrieveForIdsInBatchChildActor {
    case object ProcessingRequestGranted
    case object ProcessingCompleted
    case object RequestParentForProcessing
    case class CollectForBatchProcessing(id: I)

    def props = Props(new RetrieveResultsForIdsInBatchChildActor)
  }

  private sealed trait RetrievedResultStatus

  private case class FoundResult(result: R) extends RetrievedResultStatus
  private case object NotFound extends RetrievedResultStatus
  private case class ProcessingFailed(t: Throwable) extends RetrievedResultStatus

}


