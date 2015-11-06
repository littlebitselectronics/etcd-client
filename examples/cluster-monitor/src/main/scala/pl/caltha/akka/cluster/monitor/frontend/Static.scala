package pl.caltha.akka.cluster.monitor.frontend

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.AddressFromURIString
import akka.cluster.ClusterEvent._
import akka.cluster.MemberFactory
import akka.cluster.MemberStatus
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.ws.UpgradeToWebsocket
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.Zip

class Static extends Actor with ActorLogging {

  implicit val actorSystem = context.system

  implicit val executionContext = actorSystem.dispatcher

  implicit val materializer: Materializer = ActorMaterializer()

  def receive = Actor.ignoringBehavior

  val binding = Http().bindAndHandle(routes, "0.0.0.0", 8080)
  binding.onComplete {
    case Success(b) ⇒ log.info("Started HTTP server at {}", b.localAddress)
    case Failure(t) ⇒ log.error(t, "Failed to start HTTP server")
  }

  // drain data from any incoming message
  val wsSink: Sink[Message, _] = Flow[Message].mapAsync(1)(_ match {
    case m: TextMessage ⇒
      m.textStream.runWith(Sink.ignore)
    case m: BinaryMessage ⇒
      m.dataStream.runWith(Sink.ignore)
  }).to(Sink.ignore)

  val scenario: Seq[ClusterDomainEvent] = Seq(
    MemberUp(MemberFactory("akka.tcp://Main@172.17.0.3:2552", Set(), MemberStatus.up)),
    LeaderChanged(Some(AddressFromURIString("akka.tcp://Main@172.17.0.3:2552"))),
    RoleLeaderChanged("frontend", Some(AddressFromURIString("akka.tcp://Main@172.17.0.3:2552"))),
    RoleLeaderChanged("backend", Some(AddressFromURIString("akka.tcp://Main@172.17.0.4:2552"))),
    MemberUp(MemberFactory("akka.tcp://Main@172.17.0.4:2552", Set(), MemberStatus.up)),
    MemberUp(MemberFactory("akka.tcp://Main@172.17.0.5:2552", Set(), MemberStatus.up)),
    UnreachableMember(MemberFactory("akka.tcp://Main@172.17.0.5:2552", Set(), MemberStatus.up)),
    ReachableMember(MemberFactory("akka.tcp://Main@172.17.0.5:2552", Set(), MemberStatus.up)),
    UnreachableMember(MemberFactory("akka.tcp://Main@172.17.0.5:2552", Set(), MemberStatus.up)),
    MemberRemoved(MemberFactory("akka.tcp://Main@172.17.0.5:2552", Set(), MemberStatus.removed), MemberStatus.down))

  // send events from scenario in an infinite loop, once every 5 seconds
  val wsSource: Source[Message, _] = Source() { implicit b ⇒
    import FlowGraph.Implicits._
    val events = b.add(Source.repeat(()).mapConcat(_ ⇒ scenario.to[collection.immutable.Iterable]))
    val ticks = b.add(Source(0.seconds, 5.seconds, ()))
    val zip = b.add(Zip[ClusterDomainEvent, Unit])
    val evt = b.add(Flow[(ClusterDomainEvent, Unit)].map { case (e, _) ⇒ e })
    val format = b.add(Flow[ClusterDomainEvent].map { e ⇒
      import JsonProtocol._
      import spray.json._
      TextMessage.Strict(e.toJson.compactPrint)
    })
    events ~> zip.in0
    ticks ~> zip.in1
    zip.out ~> evt ~> format
    format.outlet
  }

  private def wsHandler: HttpRequest ⇒ HttpResponse = {
    case req: HttpRequest ⇒ req.header[UpgradeToWebsocket] match {
      case Some(upgrade) ⇒
        upgrade.handleMessagesWithSinkSource(wsSink, wsSource)
      case None ⇒ HttpResponse(400, entity = "Missing Upgrade header")
    }
  }

  def routes: Route =
    path("") {
      getFromResource("public/index.html")
    } ~ getFromResourceDirectory("public") ~ path("ws") {
      handleWith(wsHandler)
    }
}
