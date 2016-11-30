package tuktu.web.generators

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.util.Timeout
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Input
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import tuktu.api._
import play.api.Logger
import play.api.mvc.Request
import play.api.mvc.AnyContent
import play.api.Play
import play.api.libs.json.JsObject
import play.api.libs.json.Json

/**
 * Gets a webpage's content based on REST request
 */
class TuktuJSGenerator(
        resultName: String,
        processors: List[Enumeratee[DataPacket, DataPacket]],
        senderActor: Option[ActorRef]) extends TuktuBaseJSGenerator(resultName, processors, senderActor) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    // Channeling
    val (enumerator, channel) = Concurrent.broadcast[DataPacket]
    val sinkIteratee: Iteratee[DataPacket, Unit] = Iteratee.ignore
    val idString = java.util.UUID.randomUUID.toString

    // Every processor but the first gets treated as asynchronous
    for (processor <- processors.drop(1))
        processors.foreach(processor => enumerator |>> (processor compose utils.logEnumeratee(idString)) &>> sinkIteratee)

    // Options
    var add_ip: Boolean = _

    /**
     * We must somehow keep track of the sending actor of each data packet. This state is kept within this helper class that
     * is to be instantiated for each data packet
     */
    class senderReturningProcessor(sActor: ActorRef, dp: DataPacket) {
        // Create enumeratee that will send back
        val sendBackEnum: Enumeratee[DataPacket, DataPacket] = Enumeratee.map((d: DataPacket) => {
            val sourceActor = {
                senderActor match {
                    case Some(a) => a
                    case None    => sActor
                }
            }

            sourceActor ! d
            // Remove this requester from the list.
            Cache.getOrElse("JSGenerator.requesters")(collection.mutable.ListBuffer.empty[ActorRef]) -= sourceActor

            d
        })

        def runProcessor() = {
            Enumerator(dp) |>> (processors.head compose sendBackEnum compose utils.logEnumeratee(idString)) &>> sinkIteratee
        }
    }

    def receive() = {
        case ip: InitPacket => {}
        case config: JsValue => {
            add_ip = (config \ "add_ip").asOpt[Boolean].getOrElse(false)
        }
        case error: ErrorPacket => {
            // Inform all the requesters that an error occurred.
            Cache.getOrElse("JSGenerator.requesters")(collection.mutable.ListBuffer.empty[ActorRef]).foreach(_ ! error)
        }
        case sp: StopPacket => {
            // Send message to the monitor actor
            Akka.system.actorSelection("user/TuktuMonitor") ! new AppMonitorPacket(self, "done")

            val enum: Enumerator[DataPacket] = Enumerator.enumInput(Input.EOF)
            enum |>> (processors.head compose utils.logEnumeratee(idString)) &>> sinkIteratee

            channel.eofAndEnd
            self ! PoisonPill
        }
        case r: RequestPacket => {
            val request = r.request

            // Get body data and potentially the name of the next flow
            val bodyData = (request.body.asJson.getOrElse(Json.obj()).asInstanceOf[JsObject] \ "d").asOpt[JsObject].getOrElse(Json.obj())

            // Keep track of all senders, in case of errors
            Cache.getOrElse("JSGenerator.requesters", 30)(collection.mutable.ListBuffer.empty[ActorRef]) += sender

            // Set up the data packet
            val dp = DataPacket(List(Map(
                // By default, add referer, request and headers
                "headers" -> request.headers,
                "cookies" -> request.cookies.map(c => c.name -> c.value).toMap,
                Cache.getAs[String]("web.jsname").getOrElse(Play.current.configuration.getString("tuktu.jsname").getOrElse("tuktu_js_field")) -> new WebJsOrderedObject(List()))
                ++ {
                    if (r.isInitial) {
                        Map.empty
                    } else {
                        bodyData.keys.map(key => key -> utils.JsValueToAny(bodyData \ key))
                    }
                }
                ++
                Seq(
                    if (add_ip) {
                        Some("request_remoteAddress" -> request.remoteAddress)
                    } else {
                        None
                    }).flatten))

            // Push to all async processors
            channel.push(dp)

            // Send through our enumeratee
            val p = new senderReturningProcessor(sender, dp)
            p.runProcessor
        }
    }
}