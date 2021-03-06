package com.spingo.op_rabbit

import akka.actor._
import com.rabbitmq.client.ShutdownSignalException
import com.spingo.op_rabbit.RabbitControl.{Pause, Run}
import com.spingo.op_rabbit.RabbitHelpers.withChannelShutdownCatching
import com.thenewmotion.akka.rabbitmq.{Channel, ChannelActor, ChannelCreated, ChannelMessage, CreateChannel}
import java.io.IOException
import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.{Try,Failure,Success}

private [op_rabbit] class SubscriptionActor(subscription: Subscription, connection: ActorRef, initialized: Promise[Unit], closed: Promise[Unit]) extends LoggingFSM[SubscriptionActor.State, SubscriptionActor.SubscriptionPayload] {
  import SubscriptionActor._
  startWith(Disconnected, DisconnectedPayload(Paused, subscription.channelConfig.qos))

  val props = Props {
    new impl.AsyncAckingRabbitConsumer(
      name             = subscription.queue.queueName,
      subscription     = subscription.consumer)(subscription.consumer.executionContext)
  }

  private case class ChannelConnected(channel: Channel, channelActor: ActorRef)

  when(Disconnected) {
    case Event(ChannelConnected(channel, channelActor), p: DisconnectedPayload) =>
      if (p.nextState.isTerminal)
        goto(Stopped) using ConnectedPayload(channelActor, channel, p.qos, None, p.shutdownCause)
      else {
        val consumer = context.actorOf(props, "consumer")
        context watch consumer
        goto(p.nextState) using ConnectedPayload(channelActor, channel, p.qos, Some(consumer), p.shutdownCause)
      }

    case Event(Pause | Run | Stop(_,_), p: DisconnectedPayload) if p.nextState.isTerminal =>
      stay

    case Event(Pause, p: DisconnectedPayload) =>
      stay using p.copy(nextState = Paused)

    case Event(Run, p: DisconnectedPayload) =>
      stay using p.copy(nextState = Binding)

    case Event(Stop(cause, timeout), p: DisconnectedPayload) =>
      stay using p.copy(nextState = Stopping, shutdownCause = cause)

    case Event(Abort(cause), p: DisconnectedPayload) =>
      stay using p.copy(nextState = Stopped, shutdownCause = p.shutdownCause orElse cause)

    case Event(Subscription.SetQos(qos), p: DisconnectedPayload) =>
      stay using p.copy(qos = qos)
  }

  when(Running) {
    case Event(ChannelConnected(channel, _), info: ConnectedPayload) =>
      goto(Binding) using info.copy(channel = channel)
    case Event(Run, _) =>
      stay
    case Event(Pause, _) =>
      goto(Paused)
  }

  when(Binding) {
    case Event(ChannelConnected(channel, _), info: ConnectedPayload) =>
      goto(Binding) using info.copy(channel = channel)
    case Event(Run, _) =>
      stay
    case Event(Pause, _) =>
      goto(Paused)
    case Event(BindSuccess(channel), info: ConnectedPayload) =>
      if (info.channel == channel)
        goto(Running)
      else
        // we received two channels while in the Binding state. Ignore the first result.
        stay
    case Event(BindFailure(channel, ex), info: ConnectedPayload) =>
      if (info.channel == channel) {
        initialized.tryFailure(ex)
        /* propagate exception to closed future as well, as it's
         * possible for the initialization to succeed at one point, but
         * fail later. */
        closed.tryFailure(ex)

        ex match {
          case shutdownEx: ShutdownSignalException =>
            goto(Stopped) using info.copy(shutdownCause = Some(shutdownEx))
          case _ =>
            goto(Stopped)
        }
      } else {
        stay // we received two channels while in the Binding state. Ignore the first result.
      }
  }

  when(Paused) {
    case Event(Pause, _) =>
      stay
    case Event(Run, _) =>
      goto(Binding)
  }

  // Waiting for consumer actor to stop
  when(Stopping) {
    case Event(Pause | Run | Stop(_, _), _) =>
      stay
  }

  when(Stopped) {
    case _ => stay
  }

  whenUnhandled {
    case Event(BindSuccess, _) => // ignore
      stay

    case Event(ex: BindFailure, _) => // ignore
      stay

    case Event(ChannelConnected(channel, channelActor), c: ConnectedPayload) =>
      stay using c.copy(channel = channel, channelActor = channelActor)

    case Event(Subscription.SetQos(qos), c: ConnectedPayload) =>
      c.channelActor ! ChannelMessage { _.basicQos(qos) }
      stay using c.copy(qos = qos)

    case Event(Terminated(actor), payload: ConnectedPayload) if payload.consumer == Some(actor) =>
      goto(Stopped) using payload.copy(consumer = None)

    case Event(Stop(cause, timeout), payload) =>
      import context.dispatcher
      context.system.scheduler.scheduleOnce(timeout, self, Abort(None))
      goto(Stopping) using payload.copyCommon(shutdownCause = cause)

    case Event(Abort(cause), payload) =>
      goto(Stopped) using payload.copyCommon(shutdownCause = payload.shutdownCause orElse cause)

    case Event(c: ChannelCreated, _) =>
      stay

    case Event(e, s) =>
      log.error("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  onTransition {
    case Binding -> Running =>
      initialized.trySuccess(())
      nextStateData match {
        case d: ConnectedPayload =>
          d.consumer.foreach(_ ! impl.Consumer.Subscribe(d.channel))
        case _ =>
          log.error("Invalid state: cannot be Running without a ConnectedPayload")
          context stop self
      }
    case _ -> Binding =>
      nextStateData match {
        case d: ConnectedPayload =>
          doSubscribe(d)
        case _ =>
          log.error("Invalid state: cannot be Binding without a ConnectedPayload")
          context stop self
      }

    case Running -> Paused =>
      nextConnectedState { d =>
        d.consumer.foreach(_ ! impl.Consumer.Unsubscribe)
      }

    case _ -> Stopping =>
      nextStateData match {
        case d: ConnectedPayload =>
          d.consumer match {
            case None =>
              self ! Abort(None)
            case Some(consumer) =>
              consumer ! impl.Consumer.Shutdown(None)
          }
        case _ =>
          self ! Abort(None)
      }

    case _ -> Stopped =>
      context stop self
  }

  def nextConnectedState[T](fn: ConnectedPayload => T): Option[T] = {
    nextStateData match {
      case d: ConnectedPayload =>
        Some(fn(d))
      case _ =>
        log.error("Invalid state: cannot be ${state} without a ConnectedPayload")
        context stop self
        None
    }
  }

  onTermination {
    case StopEvent(_, _, payload) =>
      payload match {
        case connectionInfo: ConnectedPayload =>
          context stop connectionInfo.channelActor
          connectionInfo.consumer.foreach(context stop _)
        case _ =>
          ()
      }

      initialized.tryFailure(new RuntimeException("Subscription stopped before it had a chance to initialize"))
      closed.tryComplete(
        payload.shutdownCause.map(Failure(_)).getOrElse(Success(Unit))
      )
      stop()
  }

  initialize()

  override def preStart: Unit = {
    import ExecutionContext.Implicits.global
    val system = context.system
    connection ! CreateChannel(ChannelActor.props({(channel: Channel, channelActor: ActorRef) =>
      log.debug(s"Channel created; ${channel}")
      self ! ChannelConnected(channel, channelActor)
    }))
  }

  def doSubscribe(connectionInfo: ConnectedPayload): Unit = {
    log.debug(s"Setting up subscription to ${subscription.queue.queueName} in ${self.path}")
    val channel = connectionInfo.channel

    try {
      channel.basicQos(connectionInfo.qos)
      subscription.queue.declare(channel)
      self ! BindSuccess(channel)
    } catch {
      case ex: Throwable =>
        self ! BindFailure(channel, ex)
    }
  }
}

object SubscriptionActor {
  sealed trait State {
    val isTerminal: Boolean
  }
  case object Disconnected extends State {
    val isTerminal = false
  }
  case object Paused extends State {
    val isTerminal = false
  }
  case object Binding extends State {
    val isTerminal = false
  }
  case object Running extends State {
    val isTerminal = false
  }
  case object Stopping extends State {
    val isTerminal = true
  }
  case object Stopped extends State {
    val isTerminal = true
  }

  sealed trait Commands

  private [op_rabbit] case class BindSuccess(channel: Channel) extends Commands
  private [op_rabbit] case class BindFailure(channel: Channel, reason: Throwable) extends Commands
  case class Stop(shutdownCause: Option[Throwable], timeout: FiniteDuration = Stop.defaultTimeout) extends Commands
  object Stop {
    val defaultTimeout = 5.minutes
  }
  case class Abort(shutdownCause: Option[Throwable]) extends Commands

  sealed trait SubscriptionPayload {
    val qos: Int
    val shutdownCause: Option[Throwable]
    def copyCommon(qos: Int = qos, shutdownCause: Option[Throwable] = shutdownCause): SubscriptionPayload
  }

  case class DisconnectedPayload(nextState: State, qos: Int, shutdownCause: Option[Throwable] = None) extends SubscriptionPayload {
    def copyCommon(qos: Int = qos, shutdownCause: Option[Throwable] = shutdownCause) =
      copy(qos = qos, shutdownCause = shutdownCause)
  }

  case class ConnectedPayload(channelActor: ActorRef, channel: Channel, qos: Int, consumer: Option[ActorRef], shutdownCause: Option[Throwable] = None) extends SubscriptionPayload {
    def copyCommon(qos: Int = qos, shutdownCause: Option[Throwable] = shutdownCause) =
      copy(qos = qos, shutdownCause = shutdownCause)
  }
}
