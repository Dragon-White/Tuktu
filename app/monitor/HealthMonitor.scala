package monitor

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.concurrent.Akka
import tuktu.api.ClusterNode
import tuktu.api.HealthCheck
import tuktu.api.HealthReply
import play.api.Play

case class HealthRound()
case class AddNode(
        node: ClusterNode
)
case class DeleteNode(
        node: String
)

/**
 * Checks the health of the cluster; pings all dispatchers every now and then to see if they
 * are still alive
 */
class HealthMonitor() extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Keep track of how many times we sent a failed message to each node
    val healthChecks = collection.mutable.Map[String, Int]()
    val initcNodes = Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map())
    val homeAddress = Cache.getAs[String]("homeAddress").getOrElse("127.0.0.1")
    initcNodes.foreach(node => {
        if (node._1 != homeAddress)
            healthChecks += node._1 -> 0
    })
    
    // Get maximum number of failures
    val maxFails = Cache.getAs[Int]("mon.max_health_fails").getOrElse(Play.current.configuration.getInt("tuktu.monitor.max_health_fails").getOrElse(3))
    
    // Set up periodic health checks
    val interval = Cache.getAs[Int]("mon.health_interval").getOrElse(Play.current.configuration.getInt("tuktu.monitor.health_interval").getOrElse(30))
    Akka.system.scheduler.schedule(
            interval seconds,
            interval seconds,
            self,
            new HealthRound
    )
    
    def receive() = {
        case hr: HealthRound => {
            val cNodes = Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map())
            
            // Send out health checks to all nodes
            val clusterNodes = cNodes - homeAddress
            val futures = for ((hostname, node) <- clusterNodes) yield {
                // Return future
                val location = "akka.tcp://application@" + hostname  + ":" + node.akkaPort + "/user/TuktuDispatcher"
                (hostname, (Akka.system.actorSelection(location) ? new HealthCheck).asInstanceOf[Future[HealthReply]])
            }
            
            // Reset counter on success, increase on failure
            futures.map(future => {
                future._2.onSuccess {
                    case hr: HealthReply => healthChecks(future._1) = 0
                }
                future._2.onFailure {
                    case _ => {
                        // Increase counter and see if we need to remove
                        healthChecks(future._1) += 1
                        if (healthChecks(future._1) >= maxFails) {
                            // Remove this node from our cluster
                            cNodes -= future._1
                            // Remove from health checks
                            healthChecks -= future._1
                        }
                    }
                }
            })
        }
        case an: AddNode => {
            Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map()) +=
                an.node.host -> an.node
                
            sender ! "ok"
        }
        case dn: DeleteNode =>
            Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map()) -= dn.node
        case _ => {}
    }
}