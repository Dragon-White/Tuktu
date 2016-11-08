package controllers.web

import akka.pattern.ask
import akka.util.Timeout
import akka.actor.ActorRef
import java.net.URL
import java.nio.file._
import play.api.cache.Cache
import play.api.Play
import play.api.Play.current
import play.api.mvc._
import play.api.libs.concurrent.Akka
import play.api.libs.json.{ Json, JsArray, JsObject, JsString }
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import tuktu.api.{ DataPacket, DispatchRequest }
import scala.concurrent.Future
import tuktu.api.WebJsObject
import tuktu.api.utils
import tuktu.api.WebJsNextFlow
import tuktu.web.js.JSGeneration
import tuktu.api.WebJsOrderedObject
import tuktu.api.ErrorPacket
import tuktu.api.RequestPacket

object Application extends Controller {
    val source = scala.io.Source.fromFile(Play.application.getFile("public/images/pixel.gif"))(scala.io.Codec.ISO8859)
    val byteArray = source.map(_.toByte).toArray
    source.close()

    /**
     * Handles a polymorphic JS-request
     */
    def handleRequest(idOption: Option[String], referer: Option[String], request: Request[AnyContent],
            isInitial: Boolean, image: Boolean = false): Future[Result] = {
        if (idOption.isEmpty && referer.isEmpty)
            Future {
                if (image)
                    Ok(byteArray).as("image/gif")
                else
                    BadRequest("// No referrer found in HTTP headers.")
            }
        else {
            val id = idOption.getOrElse(referer.get)
            Cache.getAs[String]("web.repo") match {
                case None => Future {
                    if (image)
                        Ok(byteArray).as("image/gif")
                    else
                        BadRequest("// No repository for JavaScripts and Tuktu flows set in Tuktu configuration.")
                }
                case Some(webRepo) => {

                    
                    // Get actual actor
                    val actorRefMap = Cache.getAs[collection.mutable.Map[String, ActorRef]]("web.hostmap")
                        .getOrElse(collection.mutable.Map[String, ActorRef]())

                    // Check if JS actor is running
                    if (!actorRefMap.contains(id))
                        Future {
                            if (image)
                                Ok(byteArray).as("image/gif")
                            else
                                BadRequest("// The analytics script is not enabled.")
                        }
                    else {
                        implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

                        // See if we need to start a new flow or if we can send to the running actor
                        val resultFut = if (isInitial) {
                            // Send the Actor a DataPacket
                            val actorRef = actorRefMap(id)
                            
                            if (image) {
                                actorRef ! RequestPacket(request, isInitial)
                                Future {}
                            }
                            else
                                actorRef ? RequestPacket(request, isInitial)
                        } else {
                            // Since this is not the default flow, we have to see if this one is running, and start
                            // if if this is not the case

                            // Flow name must be set
                            (request.body.asJson.getOrElse(Json.obj()).asInstanceOf[JsObject] \"f").asOpt[String] match {
                                case None => {
                                    // Flow name is gone, this can't be
                                    Future {}
                                }
                                case Some(fn) => {
                                    // See if the flow for this one is already running
                                    if (!actorRefMap.contains(id + "." + fn)) {
                                        // Dispatch new config
                                        val fut = Akka.system.actorSelection("user/TuktuDispatcher") ?
                                            new DispatchRequest(
                                                webRepo.drop(Play.current.configuration.getString("tuktu.configrepo").getOrElse("configs").size)
                                                    + "/" + id + "/" + fn, None, false, true, false, None)
                                        // We must wait here
                                        val actorRef = Await.result(fut, timeout.duration).asInstanceOf[ActorRef]
                                        // Add to our map
                                        Cache.getAs[collection.mutable.Map[String, ActorRef]]("web.hostmap")
                                            .getOrElse(collection.mutable.Map[String, ActorRef]()) +=
                                            (id + "." + fn -> actorRef)
                                    }

                                    // Send the Actor a DataPacket containing the referrer
                                    actorRefMap(id + "." + fn) ? RequestPacket(request, isInitial)
                                }
                            }
                        }
                        
                        // Check if we need to add session/user ID or not
                        val cookies = (request.cookies.get("t_s_id") match {
                            case None if (Cache.getAs[Boolean]("web.set_cookies").getOrElse(true)) =>
                                List(Cookie("t_s_id", java.util.UUID.randomUUID.toString))
                            case _ => List()
                        }) ++ (request.cookies.get("t_u_id") match {
                            case None if (Cache.getAs[Boolean]("web.set_cookies").getOrElse(true)) =>
                                List(Cookie("t_u_id", java.util.UUID.randomUUID.toString, Some(Int.MaxValue)))
                            case _ => List()
                        })

                        // Return result
                        if (image) Future { Ok(byteArray).as("image/gif").withCookies(cookies: _*) }
                        else resultFut.map {
                            case dp: DataPacket =>
                                // Get all the JS elements and output them one after the other
                                val jsResult = JSGeneration.PacketToJsBuilder(dp)
                                Ok(views.js.Tuktu(jsResult._2, jsResult._1,
                                    Cache.getAs[String]("web.url").getOrElse("http://localhost:9000/") +
                                    Cache.getAs[String]("web.jsurl").getOrElse("Tuktu.js") + idOption.map('/' + _).getOrElse(""),
                                    jsResult._3))
                                .withCookies(cookies: _*)
                            case error: ErrorPacket =>
                                BadRequest("Internal error occured")
                            case _ =>
                                // Return blank
                                Ok("").as("text/javascript")
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads a JavaScript analytics script depending on referrer
     */
    def TuktuJs = Action.async { implicit request =>
        handleRequest(None, request.headers.get("referer") match {
            case None => None
            case Some(ref) => Some({
                val host = new URL(ref).getHost
                if (host.startsWith("www.")) host.drop("www.".length)
                else host
            })
        }, request, true)
    }

    /**
     * Invoke a flow based on a GET parameter that serves as ID
     */
    def TuktuJsGet(id: String) = Action.async { implicit request =>
        handleRequest(Some(id), None, request, true)
    }

    /**
     * Handles analytics by referrer
     */
    def web = Action.async { implicit request =>
        handleRequest(None, request.headers.get("referer") match {
            case None => None
            case Some(ref) => Some({
                val host = new URL(ref).getHost
                if (host.startsWith("www.")) host.drop("www.".length)
                else host
            })
        }, request, false)
    }

    /**
     * Handles analytics by id
     */
    def webGet(id: String) = Action.async { implicit request =>
        handleRequest(Some(id), None, request, false)
    }
    
    /**
     * Serves an image instead of some JS
     */
    def imgGet(id: String) = Action.async { implicit request =>
        handleRequest(Some(id), None, request, true, true)
    }
}