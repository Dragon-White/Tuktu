package tuktu.processors.meta

import tuktu.api._
import scala.concurrent.Await
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.api.cache.Cache
import java.lang.reflect.Method
import akka.actor.ActorRef
import scala.concurrent.Future
import akka.util.Timeout
import akka.actor.Props
import play.api.libs.concurrent.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import scala.concurrent.duration.DurationInt
import akka.actor.ActorLogging
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import akka.actor.PoisonPill
import akka.actor.Actor
import play.api.libs.iteratee.Concurrent
import akka.pattern.ask
import akka.remote.routing.RemoteRouterConfig
import akka.routing.RoundRobinPool
import akka.actor.Address
import akka.routing.Broadcast
import scala.util.hashing.MurmurHash3
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actor that deals with parallel processing
 */
class ConcurrentProcessorActor(start: String, processorMap: Map[String, ProcessorDefinition]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    val (enumerator, channel) = Concurrent.broadcast[DataPacket]
    val sinkIteratee: Iteratee[DataPacket, Unit] = Iteratee.ignore
    
    // Build the processor
    val (idString, processor) = {
            val pipeline = controllers.Dispatcher.buildEnums(List(start), processorMap, None, "Concurrent Processor - Unknown", true)
            (pipeline._1, pipeline._2.head)
    }

    /**
     * We must somehow keep track of the sending actor of each data packet. This state is kept within this helper class that
     * is to be instantiated for each data packet
     */
    class senderReturningProcessor(senderActor: ActorRef, dp: DataPacket) {
        // Create enumeratee that will send back
        val sendBackEnum: Enumeratee[DataPacket, DataPacket] = Enumeratee.map(dp => {
            senderActor ! dp
            dp
        })

        def runProcessor() = Enumerator(dp) |>> (processor compose sendBackEnum compose utils.logEnumeratee("")) &>> sinkIteratee
    }

    def receive() = {
        case sp: StopPacket => {
            self ! PoisonPill
        }
        case dp: DataPacket => {
            // Push to all async processors
            channel.push(dp)

            // Send through our enumeratee
            val p = new senderReturningProcessor(sender, dp)
            p.runProcessor()
        }
    }
}

/**
 * Actor that is always alive and truly async
 */
class IntermediateActor(genActor: ActorRef, node: ClusterNode, instanceCount: Int,
        start: String, processorMap: Map[String, ProcessorDefinition]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up #instanceCount actors across the nodes to use
    val router = Akka.system.actorOf(RemoteRouterConfig(RoundRobinPool(instanceCount),
            Seq(Address("akka.tcp", "application", node.host, node.akkaPort))
        ).props(Props(classOf[ConcurrentProcessorActor], start, processorMap)))
    
    // Keep track of sent DPs
    var sentDPs = new AtomicInteger(0)
    var gotStopPacket = new AtomicBoolean(false)
            
    def receive() = { 
        case dp: DataPacket => {
            sentDPs.incrementAndGet()
            println("Forwarding dp: " + sentDPs.get)
            val fut = (router ? dp)
            fut.onSuccess {
                case resultDp: DataPacket => {
                    genActor ! resultDp
                    sentDPs.decrementAndGet()
                    println("Successfully forwarded: " + sentDPs.get)
                    if (sentDPs.get == 0 && gotStopPacket.getAndSet(false)) self ! new StopPacket
                }
            }
            fut.onFailure {
                case _ => {
                    println("Failed forwarded: " + sentDPs.get)
                    sentDPs.decrementAndGet()
                    if (sentDPs.get == 0 && gotStopPacket.getAndSet(false)) self ! new StopPacket
                }
            }
        }
        case sp: StopPacket => {
            if (sentDPs.get > 0) gotStopPacket.set(true)
            else {
                println("Got a stop packet from " + sender + " -- " + self)
                router ! Broadcast(sp)
                genActor ! new StopPacket
            }
        }
    }
}

/**
 * Sets up a sub-flow concurrently and lets datapackets be processed by one of the instances,
 * allowing concurrent processing by multiple instances
 */
class ConcurrentProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    var intermediateActors = List.empty[ActorRef]
    var actorOffset = 0
    var anchorFields: Option[List[String]] = _

    override def initialize(config: JsObject) {
        // Process config
        val start = (config \ "start").as[String]
        val procs = (config \ "pipeline").as[List[JsObject]]
        anchorFields = (config \ "anchor_fields").asOpt[List[String]]
        
        // Get the number concurrent instances
        val instanceCount = (config \ "instances").as[Int]
        // Get the nodes to use
        val nodes = {
            val clusterNodes = Cache
                .getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map())
            val specifiedNodes = (config \ "nodes").asOpt[List[String]].getOrElse(clusterNodes.keys.toList)
            // Get only existing nodes
            for {
                n <- specifiedNodes
                if (clusterNodes.contains(n))
            } yield (n, clusterNodes(n))
        }

        // Define the pipeline
        val processorMap = (for (processor <- procs) yield {
            // Get all fields
            val processorId = (processor \ "id").as[String]
            val processorName = (processor \ "name").as[String]
            val processorConfig = (processor \ "config").as[JsObject]
            val resultName = (processor \ "result").as[String]
            val next = (processor \ "next").as[List[String]]

            // Create processor definition
            val procDef = new ProcessorDefinition(
                processorId,
                processorName,
                processorConfig,
                resultName,
                next)

            // Return map
            processorId -> procDef
        }).toMap
        
        // Make all the actors
        intermediateActors = for (node <- nodes) yield
            Akka.system.actorOf(Props(classOf[IntermediateActor], genActor, node._2, instanceCount, start, processorMap))
    }
    
    /**
     * Hashes anchored data to an actor
     */
    def anchorToActorHasher(packet: Map[String, Any], keys: List[String], maxSize: Int) = {
        val keyString = (for (key <- keys) yield packet(key).toString).mkString
        Math.abs(MurmurHash3.stringHash(keyString) % maxSize)
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        // If anchor fields are set, we always need to stream the data to the same actor
        anchorFields match {
            case Some(aFields) => {
                // Hash anchor fields to node/actor
                data.data.foreach(datum => {
                    val offset = anchorToActorHasher(datum, aFields, intermediateActors.size)
                    intermediateActors(offset) ! DataPacket(List(datum))
                })
            }
            case None => {
                // Send data to our actor
                intermediateActors(actorOffset) ! data
                actorOffset = (actorOffset + 1) % intermediateActors.size
            }
        }
        
        data
    }) compose Enumeratee.onEOF(() => {
        intermediateActors.foreach(_ ! new StopPacket)
    })
}